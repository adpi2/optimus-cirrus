/*
 * Morgan Stanley makes this available to you under the Apache License, Version 2.0 (the "License").
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package optimus.graph.loom;

import static optimus.CoreUtils.merge;
import static optimus.CoreUtils.stripPrefix;
import static optimus.debug.CommonAdapter.newMethod;
import static optimus.graph.loom.LoomConfig.*;
import static org.objectweb.asm.Opcodes.*;
import optimus.debug.CommonAdapter;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

public class NodeMethod extends TransformableMethod {

  private final ClassNode cls;
  final String cleanName; // Unmangled name (gets mangled when private function is accessed
  private final Type returnType; // Computed in ctor
  private final Type[] argTypes; // Computed in ctor
  private final String[] argNames; // Computed in ctor
  private final boolean isInterface; // True on interface
  public MethodNode queuedMethod;
  public MethodNode newNodeMethod;
  public int clsID;
  public int lineNumber; // first line number found in the bytecode

  // @node(exposeArgTypes = true)/@async(exposeArgTypes = true) needs to inherit this trait
  boolean trait;
  boolean asyncOnly;

  String implFieldDesc; // null if not a simple ($impl) field, else no need to create nodeClass
  String implMethodDesc; // null if not a simple ($impl) method, else no need to create nodeClass
  boolean isScenarioIndependent;

  @Override
  public String toString() {
    return this.getClass().getSimpleName() + ":" + cleanName;
  }

  public NodeMethod(
      ClassNode cls,
      String privatePrefix,
      MethodNode method,
      CompilerArgs cArgs,
      boolean hasNodeCalls) {
    super(method, cArgs, hasNodeCalls);
    this.cls = cls;
    this.cleanName = stripPrefix(method.name, privatePrefix);
    this.isInterface = CommonAdapter.isInterface(cls.access);
    this.argTypes = Type.getArgumentTypes(method.desc);
    if (method.parameters == null) this.argNames = null;
    else this.argNames = method.parameters.stream().map(p -> p.name).toArray(String[]::new);
    this.returnType = Type.getReturnType(method.desc);
  }

  public void writeNodeSyncFunc(ClassVisitor cv) {
    var cmd = isScenarioIndependent ? CMD_GETSI : CMD_GET;
    writeInvokeNewNode(cv, cmd, method, returnType);
  }

  public void writeQueuedFunc(ClassVisitor cv) {
    writeInvokeNewNode(cv, CMD_QUEUED, queuedMethod, NODE_FUTURE_TYPE);
  }

  private void writeInvokeNewNode(ClassVisitor cv, String cmd, MethodNode org, Type returnType) {
    if (newNodeMethod == null) {
      throw new IllegalStateException(
          "FATAL: Could not find matching $newNode method! " + cls.name + "." + org.name);
    }

    var desc = Type.getMethodDescriptor(returnType, argTypes);
    try (var mv = newMethod(cv, org.access, org.name, desc)) {
      mv.visitLineNumber(lineNumber, new Label());
      var newNodeDesc = Type.getMethodDescriptor(NODE_TYPE, argTypes);
      var newNodeName = newNodeMethod.name;
      var handle = new Handle(H_INVOKESPECIAL, cls.name, newNodeName, newNodeDesc, isInterface);
      invokeCmd(mv, cmd, handle, returnType, null);
    }
  }

  public void writeNewNodeFunc(ClassVisitor cv) {
    if (newNodeMethod == null) {
      throw new IllegalStateException(
          "FATAL: Could not find matching $newNode method! " + cls.name + ".");
    }
    var newDesc = Type.getMethodDescriptor(NODE_TYPE, argTypes);
    try (var mv = newMethod(cv, newNodeMethod.access, newNodeMethod.name, newDesc)) {
      if (NODE_DESC.equals(implFieldDesc) || NODE_GETTER_DESC.equals(implMethodDesc)) {
        mv.loadThis();
        var instr = NODE_DESC.equals(implFieldDesc) ? INVOKEVIRTUAL : INVOKEINTERFACE;
        mv.visitMethodInsn(
            instr, cls.name, method.name + IMPL_SUFFIX, NODE_GETTER_DESC, isInterface);
        mv.returnValue();
        return;
      }
      var needsImplSuffix = implFieldDesc != null || implMethodDesc != null;
      var methodToCall = needsImplSuffix ? method.name + IMPL_SUFFIX : method.name;
      var cmd = asyncOnly ? CMD_ASYNC : CMD_NODE; // Default....
      if (trait) cmd = asyncOnly ? CMD_ASYNC_WITH_TRAIT : CMD_NODE_WITH_TRAIT;
      else if (implFieldDesc != null) cmd = CMD_NODE_ACPN;
      else if (implMethodDesc != null) cmd = CMD_OBSERVED_VALUE_NODE;
      var handleIsInterface = implFieldDesc == null && isInterface;

      Handle orgHandle =
          new Handle(H_INVOKESPECIAL, cls.name, methodToCall, method.desc, handleIsInterface);
      invokeCmd(mv, cmd, orgHandle, NODE_TYPE, argNames);
    }
  }

  private void invokeCmd(
      CommonAdapter mv, String cmd, Handle handle, Type returnType, String[] argNames) {
    mv.loadThis(); /* this (entity) */
    mv.loadArgs(); /* method(args) */
    var bsmHandle = new Handle(H_INVOKESTATIC, NODE_FACTORY, "mfactory", BSM_DESC, false);

    var methodOwner = Type.getObjectType(cls.name);
    var descX = Type.getMethodDescriptor(returnType, merge(methodOwner, argTypes));

    var bsmParams = merge(new Object[] {handle, clsID}, argNames);
    mv.visitInvokeDynamicInsn(cmd, descX, bsmHandle, bsmParams);
    mv.returnValue();
  }
}
