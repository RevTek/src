package com.revtek.rasmo.obfuscate;

import com.revtek.rasmo.analyze.query.*;
import com.revtek.rasmo.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.lang.reflect.*;
import java.util.*;

/**
 * @author Caleb Whiting
 */
public class InlineFields implements Processor {

	private Map<String, ClassNode> classMap;

	@Override
	public void process(Map<String, ClassNode> classMap) {
		this.classMap = classMap;
		for (ClassNode node : new ArrayList<>(classMap.values())) {
			for (FieldNode field : node.fields) {
				field.access = publicize(field.access);
				for (MethodNode method : new ArrayList<>(node.methods)) {
					if (isGetterFor(node, field, method)) {
						node.methods.remove(method);
						replace(Opcodes.GETFIELD, node, field, method);
					}
					if (isSetterFor(node, field, method)) {
						node.methods.remove(method);
						replace(Opcodes.PUTFIELD, node, field, method);
					}
				}
			}
		}
	}

	private void replace(int opcode, ClassNode owner, FieldNode field, MethodNode m) {
		if (Modifier.isStatic(field.access))
			opcode -= 2;
		for (ClassNode cn : classMap.values()) {
			for (MethodNode mn : cn.methods) {
				AbstractInsnNode[] instructions = mn.instructions.toArray();
				for (AbstractInsnNode node : instructions) {
					if (node.getType() != AbstractInsnNode.METHOD_INSN) {
						continue;
					}
					MethodInsnNode min = (MethodInsnNode) node;
					List<String> owners = getChildNames(owner);
					if (owners.contains(min.owner) && min.name.equals(m.name) && min.desc.equals(m.desc)) {
						FieldInsnNode fin = new FieldInsnNode(opcode, min.owner, field.name, field.desc);
						mn.instructions.set(min, fin);
					}
				}
			}
		}
	}

	private List<String> getChildNames(ClassNode owner) {
		List<String> owners = new ArrayList<>();
		Stack<ClassNode> stack = new Stack<>();
		stack.add(owner);
		while (stack.size() > 0) {
			ClassNode c = stack.pop();
			owners.add(c.name);
			classMap.values().stream().filter(check ->
					check.superName.equals(c.name)).forEach(stack:: push);
		}
		return owners;
	}

	private boolean isGetterFor(ClassNode owner, FieldNode field, MethodNode method) {
		if (local(method.access) == local(field.access) && isTopLevel(owner, method)) {
			Type type = Type.getType(field.desc);
			Type getType = Type.getMethodType(type);
			Type methodType = Type.getMethodType(method.desc);
			if (methodType.equals(getType)) {
				List<AbstractInsnNode> instructions = getRealInstructions(method);
				NodeQuery[] filters = getPattern(true, owner, field);
				return matches(instructions, filters);
			}
		}
		return false;
	}

	private boolean isSetterFor(ClassNode owner, FieldNode field, MethodNode method) {
		if (local(method.access) == local(field.access) && isTopLevel(owner, method)) {
			Type type = Type.getType(field.desc);
			Type setType = Type.getMethodType(Type.VOID_TYPE, type);
			Type methodType = Type.getMethodType(method.desc);
			if (methodType.equals(setType)) {
				List<AbstractInsnNode> instructions = getRealInstructions(method);
				NodeQuery[] filters = getPattern(false, owner, field);
				return matches(instructions, filters);
			}
		}
		return false;
	}

	private NodeQuery[] getPattern(boolean get, ClassNode owner, FieldNode field) {
		Type type = Type.getType(field.desc);
		List<NodeQuery> predicates = new LinkedList<>();
		boolean local = local(field.access);
		if (local)
			predicates.add(new NodeQuery("opcode", Opcodes.ALOAD, "var", 0));
		if (get) {
			int opcode = local ? Opcodes.GETFIELD : Opcodes.GETSTATIC;
			predicates.add(new NodeQuery("opcode", opcode, "owner", owner.name, "name", field.name, "desc", field.desc));
			predicates.add(new NodeQuery("opcode", type.getOpcode(Opcodes.IRETURN)));
		} else {
			int opcode = local ? Opcodes.PUTFIELD : Opcodes.PUTSTATIC;
			predicates.add(new NodeQuery("opcode", type.getOpcode(Opcodes.ILOAD), "var", 0));
			predicates.add(new NodeQuery("opcode", opcode, "owner", owner.name, "name", field.name, "desc", field.desc));
			predicates.add(new NodeQuery("opcode", Opcodes.RETURN));
		}
		return predicates.toArray(new NodeQuery[predicates.size()]);
	}

	private boolean matches(List<AbstractInsnNode> list, NodeQuery... filters) {
		if (list.size() != filters.length) return false;
		for (int i = 0; i < list.size(); i++) {
			AbstractInsnNode node = list.get(i);
			NodeQuery filter = filters[i];
			if (!filter.test(node))
				return false;
		}
		return true;
	}

	private boolean isTopLevel(ClassNode owner, MethodNode method) {
		Stack<ClassNode> stack = new Stack<>();
		stack.add(owner);
		while (stack.size() > 0) {
			ClassNode node = stack.pop();
			if (node != owner && node.getMethod(method.name, method.desc) != null)
				return false;
			ClassNode superClass = getClass(node.superName);
			if (superClass != null)
				stack.push(superClass);
			if (node.interfaces != null) {
				node.interfaces.forEach(iface -> {
					ClassNode interfaceClass = getClass(iface);
					if (interfaceClass != null)
						stack.push(interfaceClass);
				});
			}
		}
		return true;
	}

	private ClassNode getClass(String name) {
		if (name == null)
			return null;
		ClassNode c = classMap.get(name);
		if (c != null)
			return c;
		return JRE.getJRE().getClassMap().get(name);
	}

	private List<AbstractInsnNode> getRealInstructions(MethodNode method) {
		List<AbstractInsnNode> instructions = new LinkedList<>();
		for (AbstractInsnNode node : method.instructions.toArray()) {
			if (node.getOpcode() != -1)
				instructions.add(node);
		}
		return instructions;
	}

	private boolean local(int access) {
		return (access & Opcodes.ACC_STATIC) == 0;
	}

	private int publicize(int access) {
		int[] flags = {Opcodes.ACC_PUBLIC, Opcodes.ACC_PRIVATE, Opcodes.ACC_PROTECTED};
		for (int flag : flags)
			if ((access & flag) == flag)
				access &= ~flag;
		access |= Opcodes.ACC_PUBLIC;
		return access;
	}

}