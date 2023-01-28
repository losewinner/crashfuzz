package edu.columbia.cs.psl.phosphor.instrumenter;

import edu.columbia.cs.psl.phosphor.Configuration;
import edu.columbia.cs.psl.phosphor.Instrumenter;
import edu.columbia.cs.psl.phosphor.TaintUtils;
import edu.columbia.cs.psl.phosphor.control.ControlFlowPropagationPolicy;
import edu.columbia.cs.psl.phosphor.control.ControlFlowStack;
import edu.columbia.cs.psl.phosphor.instrumenter.analyzer.NeverNullArgAnalyzerAdapter;
import edu.columbia.cs.psl.phosphor.runtime.*;
import edu.columbia.cs.psl.phosphor.struct.LazyArrayObjTags;
import edu.columbia.cs.psl.phosphor.struct.LazyByteArrayObjTags;
import edu.columbia.cs.psl.phosphor.struct.LazyReferenceArrayObjTags;
import edu.columbia.cs.psl.phosphor.struct.TaintedObjectWithObjTag;
import edu.columbia.cs.psl.phosphor.struct.TaintedWithObjTag;
import edu.columbia.cs.psl.phosphor.struct.harmony.util.StringBuilder;
import edu.columbia.cs.psl.phosphor.struct.harmony.util.*;
import edu.columbia.cs.psl.phosphor.struct.multid.MultiDTaintedArray;
import edu.columbia.cs.psl.phosphor.struct.multid.MultiDTaintedArrayWithObjTag;
// import edu.iscas.tcse.favtrigger.instrumenter.CodeCoverageMV;
import edu.iscas.tcse.favtrigger.instrumenter.HDFSAPIModelMV;
import edu.iscas.tcse.favtrigger.instrumenter.LevelDBAPIModelMV;
import edu.iscas.tcse.favtrigger.instrumenter.ZKAPIModelMV;
// import edu.iscas.tcse.favtrigger.instrumenter.fortest.FAVTestFileChannel;
import edu.iscas.tcse.favtrigger.instrumenter.hbase.HBaseTrackingMV;
import edu.iscas.tcse.favtrigger.instrumenter.hdfs.HDFSTrackingMV;
import edu.iscas.tcse.favtrigger.instrumenter.jdk.JRERunMode.JREType;
import edu.iscas.tcse.favtrigger.instrumenter.jdk.NIOTrackingMV;
import edu.iscas.tcse.favtrigger.instrumenter.jdk.FileOperationMV;
import edu.iscas.tcse.favtrigger.instrumenter.zk.ZKTrackingMV;
//import edu.iscas.tcse.favtrigger.instrumenter.fortest.FAVTracingNullMV;
import edu.iscas.tcse.favtrigger.instrumenter.mapred.MRProtocols;
import edu.iscas.tcse.favtrigger.instrumenter.mapred.MRTrackingMV;
import edu.iscas.tcse.favtrigger.instrumenter.yarn.YarnProtocols;
import edu.iscas.tcse.favtrigger.instrumenter.rocksdb.RocksDBWriteBatchPutMV;
import edu.iscas.tcse.favtrigger.taint.FAVTaintType;
import edu.iscas.tcse.favtrigger.taint.Source.FAVTagType;
import edu.iscas.tcse.favtrigger.tracing.CheckPath.LinkType;
import edu.iscas.tcse.favtrigger.tracing.FAVPathType;
import edu.iscas.tcse.favtrigger.instrumenter.yarn.YarnTrackingMV;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.DatagramPacket;

import static edu.columbia.cs.psl.phosphor.Configuration.TAINT_TAG_INTERNAL_NAME;
import static edu.columbia.cs.psl.phosphor.Configuration.controlFlowManager;
import static edu.columbia.cs.psl.phosphor.instrumenter.TaintMethodRecord.*;
import static org.objectweb.asm.Opcodes.ALOAD;

/**
 * CV responsibilities: Add a field to classes to track each instance's taint
 * Add a method for each primitive returning method to return the taint of that
 * return Add a field to hold temporarily the return taint of each primitive
 *
 * @author jon
 */
public class TaintTrackingClassVisitor extends ClassVisitor {

    public static final String CONTROL_STACK_DESC = Type.getDescriptor(ControlFlowStack.class);
    public static final String CONTROL_STACK_INTERNAL_NAME = Type.getInternalName(ControlFlowStack.class);
    public static final Type CONTROL_STACK_TYPE = Type.getType(ControlFlowStack.class);

    private static final boolean NATIVE_BOX_UNBOX = true;
    public static boolean IS_RUNTIME_INST = true;
    private static boolean FIELDS_ONLY = false;
    private static boolean DO_OPT = false;

    static {
        if(!DO_OPT && !IS_RUNTIME_INST) {
            System.err.println("WARN: OPT DISABLED");
        }
    }

    private List<FieldNode> fields;
    private boolean generateHashCode = false;
    private boolean generateEquals = false;
    private Map<MethodNode, MethodNode> forMore = new HashMap<>();
    private boolean hasSerialUID = false;
    private boolean addTaintField = false;
    private boolean ignoreFrames;
    private boolean generateExtraLVDebug;
    private Map<MethodNode, Type> methodsToAddWrappersForWithReturnType = new HashMap<>();
    private List<MethodNode> methodsToAddWrappersFor = new LinkedList<>();
    private List<MethodNode> methodsToAddNameOnlyWrappersFor = new LinkedList<>();
    private List<MethodNode> methodsToAddUnWrappersFor = new LinkedList<>();
    private Set<MethodNode> methodsToAddLambdaUnWrappersFor = new HashSet<>();
    private String className;
    private boolean isNormalClass;
    private boolean isInterface;
    private boolean addTaintMethod;
    private boolean isAnnotation;
    private boolean isAbstractClass;
    private boolean implementsComparable;
    private boolean implementsSerializable;
    private boolean fixLdcClass;
    private boolean isEnum;
    private boolean isUninstMethods;
    private String classSource;
    private String classDebug;
    private Set<String> aggressivelyReduceMethodSize;
    private String superName;
    private boolean isLambda;
    private HashMap<String, Method> superMethodsToOverride = new HashMap<>();
    private HashMap<String, Method> methodsWithErasedTypesToAddWrappersFor = new HashMap<>();
    private LinkedList<MethodNode> wrapperMethodsToAdd = new LinkedList<>();
    private List<FieldNode> extraFieldsToVisit = new LinkedList<>();
    private List<FieldNode> myFields = new LinkedList<>();
    private Set<String> myMethods = new HashSet<>();

    private String[] interfaces;//favtrigger

    public TaintTrackingClassVisitor(ClassVisitor cv, boolean skipFrames, List<FieldNode> fields) {
        super(Configuration.ASM_VERSION, cv);
        DO_OPT = DO_OPT && !IS_RUNTIME_INST;
        this.ignoreFrames = skipFrames;
        this.fields = fields;
    }

    public TaintTrackingClassVisitor(ClassVisitor cv, boolean skipFrames, List<FieldNode> fields, Set<String> aggressivelyReduceMethodSize) {
        this(cv, skipFrames, fields);
        this.aggressivelyReduceMethodSize = aggressivelyReduceMethodSize;
    }

    @Override
    public void visitSource(String source, String debug) {
        super.visitSource(source, debug);
        this.classSource = source;
        this.classDebug = debug;
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        this.interfaces = interfaces; //favtrigger
        addTaintField = true;
        addTaintMethod = true;
        this.generateExtraLVDebug = name.equals("java/lang/invoke/MethodType");
        this.fixLdcClass = (version & 0xFFFF) < Opcodes.V1_5;
        if(Instrumenter.IS_KAFFE_INST && name.endsWith("java/lang/VMSystem")) {
            access = access | Opcodes.ACC_PUBLIC;
        } else if(Instrumenter.IS_HARMONY_INST && name.endsWith("java/lang/VMMemoryManager")) {
            access = access & ~Opcodes.ACC_PRIVATE;
            access = access | Opcodes.ACC_PUBLIC;
        }
        if((access & Opcodes.ACC_ABSTRACT) != 0) {
            isAbstractClass = true;
        }
        if((access & Opcodes.ACC_INTERFACE) != 0) {
            addTaintField = false;
            isInterface = true;
        }
        if((access & Opcodes.ACC_ENUM) != 0) {
            isEnum = true;
            addTaintField = false;
        }

        if((access & Opcodes.ACC_ANNOTATION) != 0) {
            isAnnotation = true;
        }

        //Debugging - no more package-protected
        if((access & Opcodes.ACC_PRIVATE) == 0) {
            access = access | Opcodes.ACC_PUBLIC;
        }

        if(!superName.equals("java/lang/Object") && !Instrumenter.isIgnoredClass(superName)) {
            addTaintField = false;
            addTaintMethod = true;
        }
        if(name.equals("java/awt/image/BufferedImage") || name.equals("java/awt/image/Image")) {
            addTaintField = false;
        }
        if(addTaintField) {
            addTaintMethod = true;
        }
        if((superName.equals("java/lang/Object") || Instrumenter.isIgnoredClass(superName)) && !isInterface && !isAnnotation) {
            generateEquals = true;
            generateHashCode = true;
        }
        isLambda = name.contains("$$Lambda$");

        isNormalClass = (access & Opcodes.ACC_ENUM) == 0 && (access & Opcodes.ACC_INTERFACE) == 0;

        if((isEnum || name.equals("java/lang/Enum")) && Configuration.WITH_ENUM_BY_VAL) {
            boolean alreadyHas = false;
            for(String s : interfaces) {
                if(s.equals("java/lang/Cloneable")) {
                    alreadyHas = true;
                    break;
                }
            }
            if(!alreadyHas) {
                String[] newIntfcs = new String[interfaces.length + 1];
                System.arraycopy(interfaces, 0, newIntfcs, 0, interfaces.length);
                newIntfcs[interfaces.length] = "java/lang/Cloneable";
                interfaces = newIntfcs;
                if(signature != null) {
                    signature = signature + "Ljava/lang/Cloneable;";
                }
            }
        }
        if(isNormalClass && !Instrumenter.isIgnoredClass(name) && !FIELDS_ONLY) {
            String[] newIntfcs = new String[interfaces.length + 1];
            System.arraycopy(interfaces, 0, newIntfcs, 0, interfaces.length);
            newIntfcs[interfaces.length] = Type.getInternalName(TaintedWithObjTag.class);
            interfaces = newIntfcs;
            if(signature != null) {
                signature = signature + Type.getDescriptor(TaintedWithObjTag.class);
            }
            if(generateEquals && Configuration.WITH_HEAVY_OBJ_EQUALS_HASHCODE) {
                newIntfcs = new String[interfaces.length + 1];
                System.arraycopy(interfaces, 0, newIntfcs, 0, interfaces.length);
                Class<?> iface = TaintedObjectWithObjTag.class;
                newIntfcs[interfaces.length] = Type.getInternalName(iface);
                interfaces = newIntfcs;
                if(signature != null) {
                    signature = signature + Type.getDescriptor(iface);
                }
            }
        }
        for(String s : interfaces) {
            if(s.equals(Type.getInternalName(Comparable.class))) {
                implementsComparable = true;
            } else if(s.equals(Type.getInternalName(Serializable.class))) {
                implementsSerializable = true;
            }
        }
        super.visit(version, access, name, signature, superName, interfaces);
        this.visitAnnotation(Type.getDescriptor(TaintInstrumented.class), false);
        if(Instrumenter.isIgnoredClass(superName)) { //TODO proxy is ignored? wtf?
            //Might need to override stuff.
            Class<?> c;
            try {
                c = Class.forName(superName.replace("/", "."));
                for(Method m : c.getMethods()) {
                    if(!m.getName().endsWith(TaintUtils.METHOD_SUFFIX)) {
                        superMethodsToOverride.put(m.getName() + Type.getMethodDescriptor(m), m);
                    }
                }
            } catch(ClassNotFoundException e) {
                if(!superName.startsWith("sun/reflect/")) {
                    e.printStackTrace();
                }
            }
        }
        this.className = name;
        this.superName = superName;

        this.isUninstMethods = Instrumenter.isIgnoredClassWithStubsButNoTracking(className);
    }

    private void collectUninstrumentedInterfaceMethods(String[] interfaces) {
        String superToCheck;
        if(interfaces != null) {
            for(String itfc : interfaces) {
                superToCheck = itfc;
                try {
                    ClassNode cn = Instrumenter.classes.get(superToCheck);
                    if(cn != null) {
                        String[] s = new String[cn.interfaces.size()];
                        s = cn.interfaces.toArray(s);
                        collectUninstrumentedInterfaceMethods(s);
                        continue;
                    }
                    Class<?> c = Class.forName(superToCheck.replace("/", "."), false, Instrumenter.loader);
                    if(Instrumenter.isIgnoredClass(superToCheck)) {
                        for(Method m : c.getDeclaredMethods()) {
                            if(!Modifier.isPrivate(m.getModifiers())) {
                                superMethodsToOverride.put(m.getName() + Type.getMethodDescriptor(m), m);
                            }
                        }
                    }
                    Class<?>[] in = c.getInterfaces();
                    if(in != null && in.length > 0) {
                        String[] s = new String[in.length];
                        for(int i = 0; i < in.length; i++) {
                            s[i] = Type.getInternalName(in[i]);
                        }
                        collectUninstrumentedInterfaceMethods(s);
                    }
                } catch(Exception ex) {
                    break;
                }

            }
        }
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        if(name.equals("hashCode") && desc.equals("()I")) {
            generateHashCode = false;
        }
        if(name.equals("equals") && desc.equals("(Ljava/lang/Object;)Z")) {
            generateEquals = false;
        }
        superMethodsToOverride.remove(name + desc);
        if(name.equals("compareTo")) {
            implementsComparable = false;
        }
        this.myMethods.add(name+desc);

        //favtrigger: dong ignore special hadoop class
        if(this.className.startsWith("org/apache/commons/math3/util/FastMathLiteralArrays")
        && name.startsWith("<clinit>")){
            return super.visitMethod(access, name, desc, signature, exceptions);
        }
        //favtrigger: end
        //favtrigger: ignore special jna function for cassandra
        if(this.className.startsWith("com/sun/jna/NativeLibrary")
        && name.startsWith("<clinit>")){
            return super.visitMethod(access, name, desc, signature, exceptions);
        }
        //favtrigger: end

        boolean isImplicitLightTrackingMethod = isImplicitLightMethod(className, name, desc);
        // Hack needed for java 7 + integer->tostring fixes
        if((className.equals("java/lang/Integer") || className.equals("java/lang/Long")) && name.equals("toUnsignedString")) {
            access = (access & ~Opcodes.ACC_PRIVATE) | Opcodes.ACC_PUBLIC;
        }
        if(className.equals("java/lang/reflect/Array") && name.equals("newArray")) {
            access = (access & ~Opcodes.ACC_PRIVATE) | Opcodes.ACC_PUBLIC;
        }

        if (name.contains(TaintUtils.METHOD_SUFFIX) || (desc.contains("phosphor/struct/Tainted") && !name.contains("phosphorWrap"))) {
            if (isLambda) {
                //Do we need to force the "this" taint on to the descriptor?
                if((access & Opcodes.ACC_STATIC) == 0 && !desc.contains(Configuration.TAINT_TAG_DESC)){
                    desc = "("+Configuration.TAINT_TAG_DESC+desc.substring(1);
                    methodsToAddUnWrappersFor.add(new MethodNode(access, name, desc, signature, exceptions));
                    return new MethodVisitor(Opcodes.ASM7, super.visitMethod(access, name, desc, signature, exceptions)) {
                        @Override
                        public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
                            Object[] newLocal = new Object[local.length + 1];
                            newLocal[0] = local[0];
                            newLocal[1] = TAINT_TAG_INTERNAL_NAME;
                            System.arraycopy(local, 1, newLocal, 2, local.length - 1);
                            numLocal++;
                            super.visitFrame(type, numLocal, newLocal, numStack, stack);
                        }

                        @Override
                        public void visitVarInsn(int opcode, int var) {
                            if(var > 0) {
                                var++;
                            }
                            super.visitVarInsn(opcode, var);
                        }

                        @Override
                        public void visitIincInsn(int var, int increment) {
                            if(var > 0) {
                                var++;
                            }
                            super.visitIincInsn(var, increment);
                        }
                    };
                }
                methodsToAddUnWrappersFor.add(new MethodNode(access, name, desc, signature, exceptions));
                return super.visitMethod(access, name, desc, signature, exceptions);
            }
            //Some dynamic stuff might result in there being weird stuff here
            return new MethodVisitor(Configuration.ASM_VERSION) {
            };
        }
        if(Configuration.WITH_ENUM_BY_VAL && className.equals("java/lang/Enum") && name.equals("clone")) {
            return null;
        }
        if(Instrumenter.IS_KAFFE_INST && className.equals("java/lang/VMSystem")) {
            access = access | Opcodes.ACC_PUBLIC;
        } else if(Instrumenter.IS_HARMONY_INST && className.endsWith("java/lang/VMMemoryManager")) {
            access = access & ~Opcodes.ACC_PRIVATE;
            access = access | Opcodes.ACC_PUBLIC;
        } else if((className.equals("java/lang/Integer") || className.equals("java/lang/Long")) && name.equals("getChars")) {
            access = access | Opcodes.ACC_PUBLIC;
        }
        String originalName = name;
        if(FIELDS_ONLY) { // || isAnnotation
            return super.visitMethod(access, name, desc, signature, exceptions);
        }

        if(originalName.contains("$$INVIVO")) {
            name = name + "_orig";
        }


        //We will need to add shadow args for each parameter that is a primitive. Because that worked so well last time.
        Type[] argTypes = Type.getArgumentTypes(desc);
        LinkedList<Type> newArgTypes = new LinkedList<>();
        LinkedList<Type> wrappedArgTypes = new LinkedList<>();

        boolean isVirtual = ((Opcodes.ACC_STATIC) & access) == 0;
        boolean isRewrittenDesc = false;
        boolean addStubMethod = ((Opcodes.ACC_ABSTRACT & access) != 0) && isAbstractMethodToWrap(className, name);
        if(addStubMethod) {
            access = access & ~Opcodes.ACC_ABSTRACT;
        }
        if(isVirtual) {
            newArgTypes.add(Type.getType(Configuration.TAINT_TAG_DESC));
            isRewrittenDesc = true;
        }

        if(isLambda) {
            for(Type t : argTypes) {
                newArgTypes.add(t);
                if(t.getDescriptor().contains(CONTROL_STACK_DESC) || t.getDescriptor().contains("edu/columbia/cs/psl/phosphor/struct")
                        || t.getDescriptor().contains("Ledu/columbia/cs/psl/phosphor/runtime/Taint") || TaintUtils.isPrimitiveType(t)) {
                    final MethodVisitor cmv = super.visitMethod(access, name, desc, signature, exceptions);
                    MethodNode fullMethod = new MethodNode(Configuration.ASM_VERSION, access, name, desc, signature, exceptions) {
                        @Override
                        public void visitEnd() {
                            super.visitEnd();
                            this.accept(cmv);
                        }
                    };
                    methodsToAddNameOnlyWrappersFor.add(fullMethod);
                    return fullMethod;
                }
            }
        } else {
            for (Type t : argTypes) {
                if(TaintUtils.isWrappedType(t)) {
                    newArgTypes.add(TaintUtils.getWrapperType(t));
                    isRewrittenDesc = true;
                } else {
                    newArgTypes.add(t);
                }
                if(TaintUtils.isWrappedTypeWithErasedType(t)){ // && !StringUtils.startsWith(name,"phosphorWrapInvokeDynamic")) {
                    wrappedArgTypes.add(t);
                }
                if(TaintUtils.isShadowedType(t)) {
                    newArgTypes.add(Type.getType(TaintUtils.getShadowTaintType(t.getDescriptor())));
                    isRewrittenDesc = true;
                }
            }
            if(TaintUtils.isErasedReturnType(Type.getReturnType(desc))){
                wrappedArgTypes.add(Type.getReturnType(desc));
            }
        }
        if((Configuration.IMPLICIT_HEADERS_NO_TRACKING || Configuration.IMPLICIT_TRACKING) && !name.equals("<clinit>")) {
            isRewrittenDesc = true;
            newArgTypes.add(CONTROL_STACK_TYPE);
        }
        //If we are rewriting the return type, also add a param to pass for pre-alloc
        Type oldReturnType = Type.getReturnType(desc);
        Type newReturnType = TaintUtils.getContainerReturnType(Type.getReturnType(desc));
        if(oldReturnType.getSort() != Type.VOID) {
            newArgTypes.add(newReturnType);
            isRewrittenDesc = true;
        }
        newArgTypes.addAll(wrappedArgTypes);
        Type[] newArgs = new Type[newArgTypes.size()];
        newArgs = newArgTypes.toArray(newArgs);

        boolean requiresNoChange = !isRewrittenDesc && newReturnType.equals(Type.getReturnType(desc));
        MethodNode wrapper = new MethodNode(access, name, desc, signature, exceptions);
        if(!requiresNoChange && !name.equals("<clinit>") && !(name.equals("<init>") && !isRewrittenDesc)) {
            methodsToAddWrappersFor.add(wrapper);
        }
        String newDesc = Type.getMethodDescriptor(newReturnType, newArgs);
        if(!isUninstMethods && (access & Opcodes.ACC_NATIVE) == 0) {
            //not a native method
            LinkedList<String> addToSig = new LinkedList<>();
            if(Configuration.IMPLICIT_TRACKING || Configuration.IMPLICIT_HEADERS_NO_TRACKING) {
                addToSig.add(CONTROL_STACK_INTERNAL_NAME);
            }
            if((oldReturnType.getSort() != Type.VOID && oldReturnType.getSort() != Type.OBJECT && oldReturnType.getSort() != Type.ARRAY)) {
                addToSig.add(newReturnType.getInternalName());
            }
            signature = TaintUtils.remapSignature(signature, addToSig);
            if(!name.contains("<") && !requiresNoChange) {
                name = name + TaintUtils.METHOD_SUFFIX;
            }

            //Enforce no final taint methods. Otherwise, a subtype might try to add a wrapper for this method and throw a VerifyError
            access = access & ~Opcodes.ACC_FINAL;

            MethodVisitor mv = super.visitMethod(access, name, newDesc, signature, exceptions);
            MethodVisitor rootmV = new TaintTagFieldCastMV(mv, name);
            mv = rootmV;
            SpecialOpcodeRemovingMV specialOpcodeRemovingMV = new SpecialOpcodeRemovingMV(mv, ignoreFrames, access, className, newDesc, fixLdcClass);
            mv = specialOpcodeRemovingMV;
            NeverNullArgAnalyzerAdapter analyzer = new NeverNullArgAnalyzerAdapter(className, access, name, newDesc, mv);

            ControlFlowPropagationPolicy controlFlowPolicy = controlFlowManager.createPropagationPolicy(access, className, name, newDesc);

            MethodVisitor next = mv;
            //ReflectionHidingMV reflectionMasker = new ReflectionHidingMV(next,
            //className, name, isEnum); //favtrigger: dong commented out
            //favtrigger comment
//             TaintPassingMV tmv = new TaintPassingMV(boxFixer, access, className, name, newDesc, signature, exceptions, desc, analyzer, rootmV, wrapperMethodsToAdd, controlFlowPolicy);
            // tmv.setFields(fields);

            // FAVTestFileChannel favmv = new FAVTestFileChannel(boxFixer, access, className, name, newDesc, signature, exceptions, desc, analyzer, rootmV, wrapperMethodsToAdd, controlFlowPolicy, this.interfaces, this.superName);
            // favmv.setFields(fields);
            // FieldAccessMV fieldmv = new FieldAccessMV(favmv, access, className, name, newDesc, signature, exceptions, desc, analyzer);
            LevelDBAPIModelMV leveldbapimv = new LevelDBAPIModelMV(next, access, className, name, newDesc, signature, exceptions, desc, analyzer);
            HDFSAPIModelMV hdfsapimv = new HDFSAPIModelMV(leveldbapimv, access, className, name, newDesc, signature, exceptions, desc, analyzer, this.superName, this.interfaces);
            hdfsapimv.setFields(fields);
            ZKAPIModelMV zkapimv = new ZKAPIModelMV(hdfsapimv, access, className, name, newDesc, signature, exceptions, desc, analyzer, this.superName, this.interfaces);
            zkapimv.setFields(fields);
            MRTrackingMV mapredmv = new MRTrackingMV(zkapimv, access, className, name, newDesc, signature, exceptions, desc, analyzer, this.superName, this.interfaces);
            mapredmv.setFields(fields);
            YarnTrackingMV yarnmv = new YarnTrackingMV(mapredmv, access, className, name, newDesc, signature, exceptions, desc, analyzer, this.superName, this.interfaces);
            yarnmv.setFields(fields);
            HDFSTrackingMV dfsmv = new HDFSTrackingMV(yarnmv, access, className, name, newDesc, signature, exceptions, desc, analyzer, this.superName, this.interfaces);
            dfsmv.setFields(fields);
            HBaseTrackingMV hbmv = new HBaseTrackingMV(dfsmv, access, className, name, newDesc, signature, exceptions, desc, analyzer, this.superName, this.interfaces);
            hbmv.setFields(fields);
            ZKTrackingMV zkmv = new ZKTrackingMV(hbmv, access, className, name, newDesc, signature, exceptions, desc, analyzer, this.superName, this.interfaces);
            zkmv.setFields(fields);
            // CodeCoverageMV ccmv = new CodeCoverageMV(zkmv, access, className, name, newDesc, signature, exceptions, desc, analyzer, this.superName, this.interfaces);
            // ccmv.setFields(fields);
            NIOTrackingMV niomv = new NIOTrackingMV(zkmv, access, className, name, newDesc, signature, exceptions, desc, analyzer, this.superName, this.interfaces);
            niomv.setFields(fields);
            FileOperationMV loinfomv = new FileOperationMV(niomv, access, className, name, newDesc, signature, exceptions, desc, analyzer, this.superName, this.interfaces);
            loinfomv.setFields(fields);
            TaintPassingMV tmv = new TaintPassingMV(loinfomv, access, className, name, newDesc, signature, exceptions, desc, analyzer, rootmV, wrapperMethodsToAdd, controlFlowPolicy);
            tmv.setFields(fields);

            //ReflectionHidingMV uninstReflectionMasker = new
            //ReflectionHidingMV(mv, className, name, isEnum); //favtrigger dong
            //commented out
            UninstrumentedCompatMV umv = new UninstrumentedCompatMV(access, className, name, newDesc, oldReturnType, signature, exceptions, next, analyzer, ignoreFrames);
//            InstOrUninstChoosingMV instOrUninstChoosingMV = new
//            InstOrUninstChoosingMV(tmv, umv);
            LocalVariableManager lvs = new LocalVariableManager(access, newDesc, umv, analyzer, mv, generateExtraLVDebug);
            umv.setLocalVariableSorter(lvs);
            boolean isDisabled = Configuration.ignoredMethods.contains(className + "." + originalName + desc);

            specialOpcodeRemovingMV.setLVS(lvs);
            boolean reduceThisMethodSize = aggressivelyReduceMethodSize != null && aggressivelyReduceMethodSize.contains(name + newDesc);

            PrimitiveArrayAnalyzer primitiveArrayFixer = new PrimitiveArrayAnalyzer(className, access, name, desc, signature, exceptions, umv, isImplicitLightTrackingMethod, fixLdcClass, controlFlowPolicy.getFlowAnalyzer());
            NeverNullArgAnalyzerAdapter preAnalyzer = new NeverNullArgAnalyzerAdapter(className, access, name, desc, primitiveArrayFixer);

            controlFlowPolicy.initialize(next, lvs, analyzer);

            primitiveArrayFixer.setAnalyzer(preAnalyzer);
            tmv.setLocalVariableSorter(lvs);
//            favmv.setLocalVariableSorter(lvs);//favtrigger
            loinfomv.setLocalVariableSorter(lvs);
            zkapimv.setLocalVariableSorter(lvs);
            leveldbapimv.setLocalVariableSorter(lvs);
            yarnmv.setLocalVariableSorter(lvs);
            mapredmv.setLocalVariableSorter(lvs);
            hdfsapimv.setLocalVariableSorter(lvs);
            dfsmv.setLocalVariableSorter(lvs);
            hbmv.setLocalVariableSorter(lvs);
            zkmv.setLocalVariableSorter(lvs);
            niomv.setLocalVariableSorter(lvs);
            // ccmv.setLocalVariableSorter(lvs);
            // fieldmv.setLocalVariableSorter(lvs);
            lvs.setPrimitiveArrayAnalyzer(primitiveArrayFixer); // this guy will tell the LVS what return types to prealloc

            final MethodVisitor prev = preAnalyzer;
            MethodNode rawMethod = new MethodNode(Configuration.ASM_VERSION, access, name, desc, signature, exceptions) {
                @Override
                protected LabelNode getLabelNode(Label l) {
                    if(!Configuration.READ_AND_SAVE_BCI) {
                        return super.getLabelNode(l);
                    }
                    if(!(l.info instanceof LabelNode)) {
                        l.info = new LabelNode(l);
                    }
                    return (LabelNode) l.info;
                }

                @Override
                public void visitEnd() {
                    super.visitEnd();
                    this.accept(prev);
                }

                @Override
                public void visitFrame(int type, int nLocal, Object[] local, int nStack, Object[] stack) {
                    if(className.equals("com/sleepycat/je/log/FileManager$LogEndFileDescriptor") && name.startsWith("enqueueWrite1") && local.length > 6 && "java/lang/Object".equals(local[6])) {
                        local[6] = "[B";
                    }
                    super.visitFrame(type, nLocal, local, nStack, stack);
                }
            };

            forMore.put(wrapper, rawMethod);

            if(Configuration.extensionMethodVisitor != null) {
                try {
                    TaintAdapter custom = Configuration.extensionMethodVisitor.getConstructor(Integer.TYPE, String.class, String.class, String.class, String.class, String[].class, MethodVisitor.class,
                            NeverNullArgAnalyzerAdapter.class, String.class, String.class).newInstance(access, className, name, desc, signature, exceptions, rawMethod, null, classSource, classDebug);
                    custom.setFields(fields);
                    custom.setSuperName(superName);
                    return custom;
                } catch(InstantiationException | SecurityException | NoSuchMethodException | InvocationTargetException | IllegalArgumentException | IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
            if(addStubMethod) {
                mv.visitCode();
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ATHROW);
                mv.visitMaxs(0, 0);
                return mv;
            }
            return rawMethod;
        } else {
            // this is a native method. we want here to make a $taint method that will call the original one.
            final MethodVisitor prev = super.visitMethod(access, name, desc, signature, exceptions);
            MethodNode rawMethod = new MethodNode(Configuration.ASM_VERSION, access, name, desc, signature, exceptions) {
                @Override
                public void visitEnd() {
                    super.visitEnd();
                    this.accept(prev);
                }
            };
            forMore.put(wrapper, rawMethod);
            return rawMethod;
        }
    }

    @Override
    public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
        if(Instrumenter.isIgnoredClassWithStubsButNoTracking(className)){
            return super.visitField(access, name, desc, signature, value);
        }
        //favtrigger: add field on yarn rpc client side to store des address
        if(Configuration.USE_FAV && Configuration.FOR_YARN || Configuration.YARN_RPC) {
            if(YarnProtocols.isYarnProtocol(desc) && this.className.startsWith("org/apache/hadoop")){
                super.visitField(Opcodes.ACC_PUBLIC, TaintUtils.FAV_RPC_SOCKET, "Ljava/lang/String;", null, null);
            }
        }
        if(Configuration.USE_FAV && Configuration.FOR_MR || Configuration.MR_RPC) {
            if(MRProtocols.isMRProtocol(desc) && this.className.startsWith("org/apache/hadoop")){
                super.visitField(Opcodes.ACC_PUBLIC, TaintUtils.FAV_RPC_SOCKET, "Ljava/lang/String;", null, null);
            }
        }
        if(Configuration.USE_FAV && Configuration.FOR_HBASE || Configuration.HBASE_RPC) {
        	if(this.className.endsWith("Service$BlockingStub") && name.equals("channel")
        			&& (desc.equals("Lorg/apache/hbase/thirdparty/com/google/protobuf/BlockingRpcChannel;")
                    || desc.equals("Lcom/google/protobuf/BlockingRpcChannel;"))) {
        		access = access & ~Opcodes.ACC_PRIVATE;
                access = access & ~Opcodes.ACC_PROTECTED;
                access = access | Opcodes.ACC_PUBLIC;
        	}
        }
        //favtrigger: end
        if(shouldMakeFieldPublic(className, name)) {
            access = access & ~Opcodes.ACC_PRIVATE;
            access = access & ~Opcodes.ACC_PROTECTED;
            access = access | Opcodes.ACC_PUBLIC;
        }
        Type fieldType = Type.getType(desc);
        if(TaintUtils.isShadowedType(fieldType)) {
            if(TaintAdapter.canRawTaintAccess(className)) {
                extraFieldsToVisit.add(new FieldNode(access, name + TaintUtils.TAINT_FIELD, TaintUtils.getShadowTaintType(desc), null, null));
            } else {
                extraFieldsToVisit.add(new FieldNode(access, name + TaintUtils.TAINT_FIELD, "I", null, null));
            }
        }
        if (TaintUtils.isWrappedTypeWithSeparateField(fieldType)) {
            extraFieldsToVisit.add(new FieldNode(access, name + TaintUtils.TAINT_WRAPPER_FIELD, TaintUtils.getWrapperType(fieldType).getDescriptor(), null, null));
            if((access & Opcodes.ACC_STATIC) == 0) {
                access = access | Opcodes.ACC_TRANSIENT;
            }
        }
        if (!hasSerialUID && name.equals("serialVersionUID")) {
            hasSerialUID = true;
        }
        return super.visitField(access, name, desc, signature, value);
    }

    private boolean shouldMakeFieldPublic(String className, String name) {
        return className.equals("java/lang/String") && (name.equals("value"));
    }

    @Override
    public void visitEnd() {

        for (MethodNode mn : wrapperMethodsToAdd) {
            try {
                mn.accept(cv);
            } catch (Throwable t) {
                System.err.println("Exception while adding wrapper method " + className + "." + mn.name + mn.desc);
                t.printStackTrace();
                PrintWriter pw;
                try {
                    pw = new PrintWriter("lastMethod.txt");
                    TraceClassVisitor tcv = new TraceClassVisitor(null, new PhosphorTextifier(), pw);
                    mn.accept(tcv);
                    tcv.visitEnd();
                    pw.close();
                } catch(FileNotFoundException e1) {
                    e1.printStackTrace();
                }
            }
        }
        if((isEnum || className.equals("java/lang/Enum")) && Configuration.WITH_ENUM_BY_VAL) {
            MethodVisitor mv = super.visitMethod(Opcodes.ACC_PUBLIC, "clone", "()Ljava/lang/Object;", null, new String[]{"java/lang/CloneNotSupportedException"});
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "clone", "()Ljava/lang/Object;", false);

            mv.visitInsn(Opcodes.ARETURN);
            mv.visitEnd();
            mv.visitMaxs(0, 0);
        }
        boolean goLightOnGeneratedStuff = className.equals("java/lang/Byte");// || isLambda;
        if(!hasSerialUID && !isInterface && !goLightOnGeneratedStuff) {
            super.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "serialVersionUIDPHOSPHOR_TAG", Configuration.TAINT_TAG_DESC, null, null);
        }
        //Add a field to track the instance's taint
        if(addTaintField && !goLightOnGeneratedStuff) {
            super.visitField(Opcodes.ACC_PUBLIC, TaintUtils.TAINT_FIELD, TaintAdapter.getTagType(className).getDescriptor(), null, null);
            // Add an int field that can be used to mark an instance as visited when searching
            super.visitField(Opcodes.ACC_PUBLIC, TaintUtils.MARK_FIELD, "I", null, Integer.MIN_VALUE);
        }
        if(this.className.equals("java/lang/reflect/Method")) {
            super.visitField(Opcodes.ACC_PUBLIC, TaintUtils.TAINT_FIELD + "marked", "Z", null, 0);
            super.visitField(Opcodes.ACC_PUBLIC, TaintUtils.TAINT_FIELD + "method", "Ljava/lang/reflect/Method;", null, 0);
        } else if(this.className.equals("java/lang/Class")) {
            super.visitField(Opcodes.ACC_PUBLIC, TaintUtils.TAINT_FIELD + "marked", "Z", null, 0);
            super.visitField(Opcodes.ACC_PUBLIC, TaintUtils.TAINT_FIELD + "class", "Ljava/lang/Class;", null, 0);
            super.visitField(Opcodes.ACC_PUBLIC, TaintUtils.CLASS_OFFSET_CACHE_ADDED_FIELD, "Ledu/columbia/cs/psl/phosphor/struct/SinglyLinkedList;", null, 0);
        }
        //favtrigger: add a field for classes that need to record critical Taint
        //info, avoid dead loop
        if(Instrumenter.isClassNeedToDecideIfRecordTaint(this.className)){
            super.visitField(Opcodes.ACC_PUBLIC, TaintUtils.FAV_RECORD_TAG, "Z", null, 1);
        }
        if(this.className.equals("java/io/DataInputStream")){
            super.visitField(Opcodes.ACC_PUBLIC, TaintUtils.FAV_TAINT_PATH, "Ljava/lang/String;", null, null);
        }
        if(this.className.equals("java/nio/ByteBuffer")){
            super.visitField(Opcodes.ACC_PUBLIC, TaintUtils.FAV_BUFFER_MSGID_FIELD, "I", null, Integer.MAX_VALUE);
        }
        if(this.className.equals("org/apache/hadoop/fs/FSDataOutputStream")
            || this.className.equals("org/apache/hadoop/fs/FSDataInputStream")) {
                super.visitField(Opcodes.ACC_PUBLIC, TaintUtils.FAV_HDFSSTREAM_PATH, "Ljava/lang/String;", null, null);
        }
        if(this.className.equals("org/fusesource/leveldbjni/internal/NativeDB")
            || this.className.equals("org/fusesource/leveldbjni/internal/NativeWriteBatch")) {
                super.visitField(Opcodes.ACC_PUBLIC, TaintUtils.FAV_PATH, "Ljava/lang/String;", null, null);
        }
        if (this.className.equals("org/rocksdb/WriteBatch")) {
            super.visitField(Opcodes.ACC_PUBLIC, RocksDBWriteBatchPutMV.combinedTaintFiledName, "Ledu/columbia/cs/psl/phosphor/runtime/Taint;", null, null); // Taint.emptyTaint()
        }
        if(this.className.equals("java/io/OutputStream")){
            super.visitField(Opcodes.ACC_PUBLIC, "favAddr", "Ljava/net/InetAddress;", null, null);
        }
        if(this.className.equals("java/io/InputStream")){
            super.visitField(Opcodes.ACC_PUBLIC, "favAddr", "Ljava/net/InetAddress;", null, null);
        }
        //favtrigger end
        for(FieldNode fn : extraFieldsToVisit) {
            if(className.equals("java/lang/Byte") && !fn.name.startsWith("value")) {
                continue;
            }
            if(isNormalClass) {
                fn.access = fn.access & ~Opcodes.ACC_FINAL;
                fn.access = fn.access & ~Opcodes.ACC_PRIVATE;
                fn.access = fn.access & ~Opcodes.ACC_PROTECTED;
                fn.access = fn.access | Opcodes.ACC_PUBLIC;
            }
            if((fn.access & Opcodes.ACC_STATIC) != 0) {
                if(fn.desc.equals("I")) {
                    super.visitField(fn.access, fn.name, fn.desc, fn.signature, 0);
                } else {
                    super.visitField(fn.access, fn.name, fn.desc, fn.signature, null);
                }
            } else {
                super.visitField(fn.access, fn.name, fn.desc, fn.signature, null);
            }
        }
        if(FIELDS_ONLY) {
            return;
        }
        if((isAbstractClass || isInterface) && implementsComparable && !goLightOnGeneratedStuff) {
            //Need to add this to interfaces so that we can call it on the interface
            if((Configuration.IMPLICIT_HEADERS_NO_TRACKING || Configuration.IMPLICIT_TRACKING)) {
                super.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "compareTo$$PHOSPHORTAGGED", "(" + Configuration.TAINT_TAG_DESC + "Ljava/lang/Object;" + Configuration.TAINT_TAG_DESC + CONTROL_STACK_DESC + Configuration.TAINTED_INT_DESC + ")" + Configuration.TAINTED_INT_DESC, null, null);
            } else {
                super.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "compareTo$$PHOSPHORTAGGED", "(" + Configuration.TAINT_TAG_DESC + "Ljava/lang/Object;" + Configuration.TAINT_TAG_DESC + Configuration.TAINTED_INT_DESC + ")" + Configuration.TAINTED_INT_DESC, null, null);
            }
        }

        if(generateEquals && !goLightOnGeneratedStuff) {

            superMethodsToOverride.remove("equals(Ljava/lang/Object;)Z");
            methodsToAddWrappersFor.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_NATIVE, "equals", "(Ljava/lang/Object;)Z", null, null));
            MethodVisitor mv;
            mv = super.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, "equals", "(Ljava/lang/Object;)Z", null, null);
            mv.visitCode();
            Label start = new Label();
            Label end = new Label();
            mv.visitLabel(start);
            if(isLambda) {
                mv.visitVarInsn(ALOAD, 0);
                mv.visitVarInsn(ALOAD, 1);
                Label eq = new Label();
                mv.visitJumpInsn(Opcodes.IF_ACMPEQ, eq);
                mv.visitInsn(Opcodes.ICONST_0);
                mv.visitInsn(Opcodes.IRETURN);
                mv.visitLabel(eq);
                mv.visitFrame(Opcodes.F_NEW, 2, new Object[]{className, "java/lang/Object"}, 0, new Object[]{});
                mv.visitInsn(Opcodes.ICONST_1);
            } else {
                mv.visitVarInsn(ALOAD, 0);
                mv.visitVarInsn(ALOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "equals", "(Ljava/lang/Object;)Z", false);
            }
            mv.visitLabel(end);
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitLocalVariable("this", "L" + className + ";", null, start, end, 0);
            mv.visitLocalVariable("other", "Ljava/lang/Object;", null, start, end, 1);

            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        if(generateHashCode && !goLightOnGeneratedStuff) {
            superMethodsToOverride.remove("hashCode()I");
            methodsToAddWrappersFor.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_NATIVE, "hashCode", "()I", null, null));
            MethodVisitor mv;
            mv = super.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, "hashCode", "()I", null, null);
            mv.visitCode();
            Label start = new Label();
            Label end = new Label();
            mv.visitLabel(start);
            if(isLambda) {
                mv.visitInsn(Opcodes.ICONST_0);
            } else {
                mv.visitVarInsn(ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "hashCode", "()I", false);
            }
            mv.visitLabel(end);
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitLocalVariable("this", "L" + className + ";", null, start, end, 0);

            mv.visitMaxs(0, 0);
            mv.visitEnd();

        }
        if(addTaintMethod) {
            if(isInterface) {
                super.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "get" + TaintUtils.TAINT_FIELD, "()Ljava/lang/Object;", null, null);
                //favtrigger: the method name must be favPHOSPHOR_TAG, or error appears
                super.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "fav" + TaintUtils.TAINT_FIELD, "()Ljava/lang/Object;", null, null);
                //favtrigger end
                super.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "set" + TaintUtils.TAINT_FIELD, "(Ljava/lang/Object;)V", null, null);
            } else {
                MethodVisitor mv;
                mv = super.visitMethod(Opcodes.ACC_PUBLIC, "get" + TaintUtils.TAINT_FIELD, "()Ljava/lang/Object;", null, null);
                mv = new TaintTagFieldCastMV(mv, "get" + TaintUtils.TAINT_FIELD);
                mv.visitCode();
                mv.visitVarInsn(ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETFIELD, className, TaintUtils.TAINT_FIELD, Configuration.TAINT_TAG_DESC);

                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();

                //favtrigger: add method to get content taints
                // mv = super.visitMethod(Opcodes.ACC_PUBLIC, "fav" + TaintUtils.TAINT_FIELD, "()Ljava/lang/Object;", null, null);
                // mv = new TaintTagFieldCastMV(mv, "fav" + TaintUtils.TAINT_FIELD);
                // mv.visitCode();
                // mv.visitVarInsn(ALOAD, 0);
                // mv.visitFieldInsn(Opcodes.GETFIELD, className, TaintUtils.TAINT_FIELD, Configuration.TAINT_TAG_DESC);

                // mv.visitInsn(Opcodes.ARETURN);
                // mv.visitMaxs(0, 0);
                // mv.visitEnd();

                mv = super.visitMethod(Opcodes.ACC_PUBLIC, "fav" + TaintUtils.TAINT_FIELD, "()Ljava/lang/Object;", null, null);
                mv = new TaintTagFieldCastMV(mv, "fav" + TaintUtils.TAINT_FIELD);
                mv.visitCode();
                mv.visitVarInsn(ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETFIELD, className, TaintUtils.TAINT_FIELD, Configuration.TAINT_TAG_DESC);

                for(FieldNode f:myFields) {
                	Type fieldType = Type.getType(f.desc);
                    if(TaintUtils.isShadowedType(fieldType)) {
                    	if(TaintUtils.isPrimitiveType(fieldType)) {
                    		mv.visitVarInsn(ALOAD, 0);
                    		mv.visitFieldInsn(Opcodes.GETFIELD, className, f.name + TaintUtils.TAINT_FIELD, Configuration.TAINT_TAG_DESC);
                    		COMBINE_TAGS.delegateVisit(mv);
                    	} else if (!TaintUtils.isWrappedType(fieldType) && !(fieldType.getInternalName().startsWith("edu/columbia/cs/psl/phosphor")||fieldType.getInternalName().startsWith("edu/iscas/tcse/favtrigger"))) {
                    		mv.visitVarInsn(ALOAD, 0);
                    		mv.visitFieldInsn(Opcodes.GETFIELD, className, f.name, f.desc);
                    		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, fieldType.getInternalName(), "getFAV_CONTENT_TAINT", "()Ljava/lang/Object;", false);
                    		mv.visitTypeInsn(Opcodes.CHECKCAST, Configuration.TAINT_TAG_INTERNAL_NAME);
                    		COMBINE_TAGS.delegateVisit(mv);
                    	}
                    }
                    if (TaintUtils.isWrappedTypeWithSeparateField(fieldType)) {
                    	if(TaintUtils.isPrimitiveArrayType(fieldType)) {
                    		mv.visitVarInsn(ALOAD, 0);
                    		mv.visitFieldInsn(Opcodes.GETFIELD, className, f.name + TaintUtils.TAINT_WRAPPER_FIELD, TaintUtils.getWrapperType(fieldType).getDescriptor());
                    		mv.visitFieldInsn(Opcodes.GETFIELD, TaintUtils.getWrapperType(fieldType).getInternalName(), "taints", "["+Configuration.TAINT_TAG_DESC);
                    		COMBINE_TAGS_ARRAY.delegateVisit(mv);
                    		COMBINE_TAGS.delegateVisit(mv);
                    	} else if (fieldType != null && fieldType.getSort() == Type.ARRAY && fieldType.getDimensions() == 1 && fieldType.getElementType().getSort() == Type.OBJECT
                    			&& (!(fieldType.getElementType().getInternalName().startsWith("edu/columbia/cs/psl/phosphor")||fieldType.getElementType().getInternalName().startsWith("edu/iscas/tcse/favtrigger")))) {
//                    		mv.visitFieldInsn(Opcodes.GETFIELD, className, f.name + TaintUtils.TAINT_WRAPPER_FIELD, TaintUtils.getWrapperType(fieldType).getDescriptor());
//                    		mv.visitFieldInsn(Opcodes.GETFIELD, TaintUtils.getWrapperType(fieldType).getInternalName(), "val", "[Ljava/lang/Object;");
//                    		mv.visitVarInsn(Opcodes.ASTORE, 1);
//
//                    		mv.visitVarInsn(Opcodes.ASTORE, 2);
//
//                    		Label l2 = new Label();
//                            mv.visitLabel(l2);
//                            mv.visitVarInsn(Opcodes.ALOAD, 1);
//                            mv.visitVarInsn(Opcodes.ASTORE, 3);
//                            mv.visitVarInsn(Opcodes.ALOAD, 3);
//                            mv.visitInsn(Opcodes.ARRAYLENGTH);
//                            mv.visitVarInsn(Opcodes.ISTORE, 4);
//                            mv.visitInsn(Opcodes.ICONST_0);
//                            mv.visitVarInsn(Opcodes.ISTORE, 5);
//                            Label l3 = new Label();
//                            mv.visitLabel(l3);
//                            mv.visitFrame(Opcodes.F_FULL, 6, new Object[]{className, "[Ljava/lang/Object;", Configuration.TAINT_TAG_INTERNAL_NAME, "[Ljava/lang/Object;", Opcodes.INTEGER, Opcodes.INTEGER}, 0, new Object[]{});
//
//                            mv.visitVarInsn(Opcodes.ILOAD, 5);
//                            mv.visitVarInsn(Opcodes.ILOAD, 4);
//                            Label l4 = new Label();
//                            mv.visitJumpInsn(Opcodes.IF_ICMPGE, l4);
//                            mv.visitVarInsn(ALOAD, 3);
//                            mv.visitVarInsn(Opcodes.ILOAD, 5);
//                            mv.visitInsn(Opcodes.AALOAD);
//                            mv.visitVarInsn(Opcodes.ASTORE, 6);
//                            Label l5 = new Label();
//                            mv.visitLabel(l5);
//                            mv.visitVarInsn(Opcodes.ALOAD, 2);
//                            mv.visitVarInsn(Opcodes.ALOAD, 6);
//                            mv.visitTypeInsn(Opcodes.CHECKCAST, fieldType.getElementType().getInternalName());
//                            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, fieldType.getElementType().getInternalName(), "getFAV_CONTENT_TAINT", Configuration.TAINT_TAG_DESC, false);
//                            COMBINE_TAGS.delegateVisit(mv);
//                            mv.visitVarInsn(Opcodes.ASTORE, 2);
//
//                            Label l6 = new Label();
//                            mv.visitLabel(l6);
//                            mv.visitLineNumber(37, l6);
//                            mv.visitIincInsn(5, 1);
//                            mv.visitJumpInsn(Opcodes.GOTO, l3);
//                            mv.visitLabel(l4);
//                            mv.visitFrame(Opcodes.F_CHOP, 3, null, 0, null);
//                            mv.visitVarInsn(Opcodes.ALOAD, 2);
                    	} //NOTE: we do not consider multi-dimension arrays here
                    }
                }
//favbug
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();
                //favtrigger: end

                mv = super.visitMethod(Opcodes.ACC_PUBLIC, "set" + TaintUtils.TAINT_FIELD, "(Ljava/lang/Object;)V", null, null);
                mv = new TaintTagFieldCastMV(mv, "set" + TaintUtils.TAINT_FIELD);

                mv.visitCode();
                Configuration.taintTagFactory.generateSetTag(mv, className);
                if(className.equals("java/lang/String")) {
                    //Also overwrite the taint tag of all of the chars behind this string

                    Type taintType = MultiDTaintedArray.getTypeForType(Type.getType(char[].class));
                    mv.visitFieldInsn(Opcodes.GETSTATIC, Type.getInternalName(Configuration.class), "autoTainter", Type.getDescriptor(TaintSourceWrapper.class));
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitInsn(Opcodes.DUP);
                    mv.visitFieldInsn(Opcodes.GETFIELD, className, "value", "[C");
                    //A
                    mv.visitTypeInsn(Opcodes.NEW, taintType.getInternalName());
                    //A T
                    mv.visitInsn(Opcodes.DUP_X1);
                    //T A T
                    mv.visitInsn(Opcodes.SWAP);
                    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, taintType.getInternalName(), "<init>", "([C)V", false);
                    //T
                    mv.visitInsn(Opcodes.DUP_X1);
                    mv.visitFieldInsn(Opcodes.PUTFIELD, className, "value" + TaintUtils.TAINT_WRAPPER_FIELD, taintType.getDescriptor());

                    mv.visitVarInsn(ALOAD, 1);
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(TaintSourceWrapper.class), "combineTaintsOnArray", "(Ljava/lang/Object;" + Configuration.TAINT_TAG_DESC + ")V", false);
                } else if((className.equals(TaintPassingMV.INTEGER_NAME) || className.equals(TaintPassingMV.LONG_NAME)
                        || className.equals(TaintPassingMV.FLOAT_NAME) || className.equals(TaintPassingMV.DOUBLE_NAME))) {
                    //For primitive types, also set the "value" field
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitVarInsn(ALOAD, 1);
                    mv.visitFieldInsn(Opcodes.PUTFIELD, className, "value" + TaintUtils.TAINT_FIELD, Configuration.TAINT_TAG_DESC);
                }
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();

                //favtrigger:
//                int acc = Opcodes.ACC_PUBLIC;
//                String favDesc = "()Ljava/lang/Object;";
//                mv = super.visitMethod(Opcodes.ACC_PUBLIC, "getFAV_CONTENT_TAINT", "()Ljava/lang/Object;", null, null);
//                NeverNullArgAnalyzerAdapter an = new NeverNullArgAnalyzerAdapter(className, acc, "getFAV_CONTENT_TAINT", favDesc, mv);
//                MethodVisitor soc = new SpecialOpcodeRemovingMV(an, ignoreFrames, acc, className, favDesc, fixLdcClass);
//                LocalVariableManager lvs = new LocalVariableManager(acc, favDesc, soc, an, mv, generateExtraLVDebug);
//                LinkedList<LocalVariableNode> lvsToVisit = new LinkedList<>();
//                Type returnType = Type.getReturnType(favDesc);
//                lvs.setPrimitiveArrayAnalyzer(new PrimitiveArrayAnalyzer(returnType));
//                GeneratorAdapter ga = new GeneratorAdapter(lvs, acc, "getFAV_CONTENT_TAINT", favDesc);
//                LabelNode start = new LabelNode(new Label());
//                LabelNode end = new LabelNode(new Label());
//                ga.visitCode();
//                ga.visitLabel(start.getLabel());
//
//                if(className.equals("java/lang/String")) {
//                    Type taintType = MultiDTaintedArray.getTypeForType(Type.getType(char[].class));
//                    ga.visitVarInsn(ALOAD, 0);
//                    ga.visitFieldInsn(Opcodes.GETFIELD, className, "value" + TaintUtils.TAINT_WRAPPER_FIELD, taintType.getDescriptor());
//                    ga.visitFieldInsn(Opcodes.GETFIELD, taintType.getInternalName(), "taints", "["+Configuration.TAINT_TAG_DESC);
//                    COMBINE_TAGS_ARRAY.delegateVisit(ga);
//                } else if ((className.equals(TaintPassingMV.INTEGER_NAME) || className.equals(TaintPassingMV.LONG_NAME)
//                        || className.equals(TaintPassingMV.FLOAT_NAME) || className.equals(TaintPassingMV.DOUBLE_NAME))) {
//                    ga.visitVarInsn(ALOAD, 0);
//                    ga.visitFieldInsn(Opcodes.GETFIELD, className, TaintUtils.TAINT_FIELD, Configuration.TAINT_TAG_DESC);
//                } else {
//                    NEW_EMPTY_TAINT.delegateVisit(mv);
//                    for(FieldNode fn : fields) {
//                        Type t = Type.getType(fn.desc);
//                        if(TaintUtils.isShadowedType(t)) {
//                            if(TaintUtils.isPrimitiveType(t) || t.getInternalName().equals(TaintPassingMV.INTEGER_NAME)
//                                    || t.getInternalName().equals(TaintPassingMV.LONG_NAME)
//                                    || t.getInternalName().equals(TaintPassingMV.FLOAT_NAME)
//                                    || t.getInternalName().equals(TaintPassingMV.DOUBLE_NAME)) {
//                                String shadowDesc = TaintUtils.getShadowTaintType(fn.desc);
//                                ga.visitVarInsn(ALOAD, 0);
//                                ga.visitFieldInsn(Opcodes.GETFIELD, className, fn.name + TaintUtils.TAINT_FIELD, shadowDesc);
//                                COMBINE_TAGS.delegateVisit(ga);
//                            }
//                        }
//                        if (TaintUtils.isWrappedTypeWithSeparateField(t)) {
//                            String wrapperDesc = TaintUtils.getWrapperType(t).getDescriptor();
//                            Type wrapperType = Type.getType(wrapperDesc);
//                            if(TaintUtils.isPrimitiveArrayType(t)) {
//                                ga.visitVarInsn(ALOAD, 0);
//                                ga.visitFieldInsn(Opcodes.GETFIELD, className, fn.name + TaintUtils.TAINT_WRAPPER_FIELD, wrapperDesc);
//                                ga.visitFieldInsn(Opcodes.GETFIELD, wrapperType.getInternalName(), "taints", "["+Configuration.TAINT_TAG_DESC);
//                                COMBINE_TAGS_ARRAY.delegateVisit(ga);
//                                COMBINE_TAGS.delegateVisit(ga);
//                            } else {
//                                ga.visitVarInsn(ALOAD, 0);
//                                ga.visitFieldInsn(Opcodes.GETFIELD, className, fn.name + TaintUtils.TAINT_WRAPPER_FIELD, wrapperDesc);
//                                ga.visitFieldInsn(Opcodes.GETFIELD, wrapperType.getInternalName(), "val", "[Ljava/lang/Object;");
//                                int objArray = lvs.getTmpLV();
//                                ga.visitVarInsn(Opcodes.ASTORE, objArray);
//                                ga.visitInsn(Opcodes.ICONST_0);
//                                int idx = lvs.getTmpLV();
//                                ga.visitVarInsn(Opcodes.ISTORE, idx);
//                                Label forBegin = new Label();
//                                ga.visitLabel(forBegin);
//                                ga.visitFrame(Opcodes.F_APPEND, 2, new Object[]{"[Ljava/lang/String;", Opcodes.INTEGER}, 0, null);
//                                ga.visitVarInsn(ILOAD, 2);
//                                ga.visitVarInsn(ALOAD, 1);
//                            }
//                        }
//                    }
//                }
//                ga.visitInsn(Opcodes.ARETURN);
//                ga.visitLabel(end.getLabel());
//                ga.returnValue();
//                for(LocalVariableNode n : lvsToVisit) {
//                    n.accept(ga);
//                }
//                ga.visitMaxs(0, 0);
//                ga.visitEnd();
//                faverror
                //favtrigger end
            }
        }

        //favtrigger: add method to set a fileoutputstream object's
        //FAV_RECORD_TAG to false if not null
        if(this.className.equals("java/io/FileOutputStream")){
        	MethodVisitor mv;
            int acc = Opcodes.ACC_PUBLIC;
            String favDesc = "()V";
            mv = super.visitMethod(acc, TaintUtils.FAV_FILEOUT_NOTRECORD, favDesc, null, null);
            NeverNullArgAnalyzerAdapter an = new NeverNullArgAnalyzerAdapter(className, acc, TaintUtils.FAV_FILEOUT_NOTRECORD, favDesc, mv);
            MethodVisitor soc = new SpecialOpcodeRemovingMV(an, ignoreFrames, acc, className, favDesc, fixLdcClass);
            LocalVariableManager lvs = new LocalVariableManager(acc, favDesc, soc, an, mv, generateExtraLVDebug);
            LinkedList<LocalVariableNode> lvsToVisit = new LinkedList<>();
            Type returnType = Type.getReturnType(favDesc);
            lvs.setPrimitiveArrayAnalyzer(new PrimitiveArrayAnalyzer(returnType));
            GeneratorAdapter ga = new GeneratorAdapter(lvs, acc, TaintUtils.FAV_FILEOUT_NOTRECORD, favDesc);
            LabelNode start = new LabelNode(new Label());
            LabelNode end = new LabelNode(new Label());
            ga.visitCode();
            ga.visitLabel(start.getLabel());

            if((acc & Opcodes.ACC_STATIC) == 0) {
                ga.visitVarInsn(ALOAD, 0);
                lvsToVisit.add(new LocalVariableNode("this", "L" + className + ";", null, start, end, 0));
            }

            Label nullOutStream = new Label();
            ga.visitVarInsn(Opcodes.ALOAD, 0);
            ga.visitJumpInsn(Opcodes.IFNULL, nullOutStream);

            ga.visitVarInsn(Opcodes.ALOAD, 0);
            ga.visitLdcInsn(0);  //set FAV_RECORD_TAG to false, avoid dead loop
            ga.visitFieldInsn(Opcodes.PUTFIELD, "java/io/FileOutputStream", TaintUtils.FAV_RECORD_TAG, "Z");

            ga.visitLabel(nullOutStream);

            ga.visitLabel(end.getLabel());
            ga.returnValue();
            for(LocalVariableNode n : lvsToVisit) {
                n.accept(ga);
            }
            ga.visitMaxs(0, 0);
            ga.visitEnd();
        }
        //favtrigger: add method to get taints in hbPHOSPHOR_WRAPPER java.nio.ByteBuffer
        if(this.className.equals("java/nio/ByteBuffer")){
        	MethodVisitor mv;
            int acc = Opcodes.ACC_PUBLIC;
            String favDesc = "()["+Configuration.TAINT_TAG_DESC;
            mv = super.visitMethod(acc, TaintUtils.FAV_GETBUFFERTAINTS_MT, favDesc, null, null);
            NeverNullArgAnalyzerAdapter an = new NeverNullArgAnalyzerAdapter(className, acc, TaintUtils.FAV_GETBUFFERTAINTS_MT, favDesc, mv);
            MethodVisitor soc = new SpecialOpcodeRemovingMV(an, ignoreFrames, acc, className, favDesc, fixLdcClass);
            LocalVariableManager lvs = new LocalVariableManager(acc, favDesc, soc, an, mv, generateExtraLVDebug);
            LinkedList<LocalVariableNode> lvsToVisit = new LinkedList<>();
            Type returnType = Type.getReturnType(favDesc);
            lvs.setPrimitiveArrayAnalyzer(new PrimitiveArrayAnalyzer(returnType));
            GeneratorAdapter ga = new GeneratorAdapter(lvs, acc, TaintUtils.FAV_GETBUFFERTAINTS_MT, favDesc);
            LabelNode start = new LabelNode(new Label());
            LabelNode end = new LabelNode(new Label());
            ga.visitCode();
            ga.visitLabel(start.getLabel());

            if((acc & Opcodes.ACC_STATIC) == 0) {
                ga.visitVarInsn(ALOAD, 0);
                lvsToVisit.add(new LocalVariableNode("this", "L" + className + ";", null, start, end, 0));
            }

            ga.visitVarInsn(ALOAD, 0);
            ga.visitFieldInsn(Opcodes.GETFIELD, className, "hbPHOSPHOR_WRAPPER", Type.getDescriptor(LazyByteArrayObjTags.class));
            ga.visitTypeInsn(Opcodes.CHECKCAST,Type.getInternalName(LazyByteArrayObjTags.class));
            ga.visitFieldInsn(Opcodes.GETFIELD, Type.getInternalName(LazyByteArrayObjTags.class), "taints", "["+Configuration.TAINT_TAG_DESC);

            ga.visitLabel(end.getLabel());
            ga.returnValue();
            for(LocalVariableNode n : lvsToVisit) {
                n.accept(ga);
            }
            ga.visitMaxs(0, 0);
            ga.visitEnd();
        }
        if(this.className.equals("java/nio/ByteBuffer")){
        	MethodVisitor mv;
            int acc = Opcodes.ACC_PUBLIC;
            String favDesc = "()"+Type.getDescriptor(LazyByteArrayObjTags.class);
            mv = super.visitMethod(acc, TaintUtils.FAV_GETBUFFERSHADOW_MT, favDesc, null, null);
            NeverNullArgAnalyzerAdapter an = new NeverNullArgAnalyzerAdapter(className, acc, TaintUtils.FAV_GETBUFFERSHADOW_MT, favDesc, mv);
            MethodVisitor soc = new SpecialOpcodeRemovingMV(an, ignoreFrames, acc, className, favDesc, fixLdcClass);
            LocalVariableManager lvs = new LocalVariableManager(acc, favDesc, soc, an, mv, generateExtraLVDebug);
            LinkedList<LocalVariableNode> lvsToVisit = new LinkedList<>();
            Type returnType = Type.getReturnType(favDesc);
            lvs.setPrimitiveArrayAnalyzer(new PrimitiveArrayAnalyzer(returnType));
            GeneratorAdapter ga = new GeneratorAdapter(lvs, acc, TaintUtils.FAV_GETBUFFERSHADOW_MT, favDesc);
            LabelNode start = new LabelNode(new Label());
            LabelNode end = new LabelNode(new Label());
            ga.visitCode();
            ga.visitLabel(start.getLabel());

            if((acc & Opcodes.ACC_STATIC) == 0) {
                ga.visitVarInsn(ALOAD, 0);
                lvsToVisit.add(new LocalVariableNode("this", "L" + className + ";", null, start, end, 0));
            }

            ga.visitVarInsn(ALOAD, 0);
            ga.visitFieldInsn(Opcodes.GETFIELD, className, "hbPHOSPHOR_WRAPPER", Type.getDescriptor(LazyByteArrayObjTags.class));
            ga.visitTypeInsn(Opcodes.CHECKCAST,Type.getInternalName(LazyByteArrayObjTags.class));

            ga.visitLabel(end.getLabel());
            ga.returnValue();
            for(LocalVariableNode n : lvsToVisit) {
                n.accept(ga);
            }
            ga.visitMaxs(0, 0);
            ga.visitEnd();
        }
        if(Configuration.USE_FAV && Configuration.FOR_HBASE || Configuration.HBASE_RPC) {
        	if(this.className.equals("org/apache/hadoop/hbase/ipc/AbstractRpcClient$BlockingRpcChannelImplementation")){
            	MethodVisitor mv;
                int acc = Opcodes.ACC_PUBLIC;
                String favDesc = "()Ljava/net/InetSocketAddress;";
                mv = super.visitMethod(acc, "getAddr$$FAV", favDesc, null, null);
                NeverNullArgAnalyzerAdapter an = new NeverNullArgAnalyzerAdapter(className, acc, "getAddr$$FAV", favDesc, mv);
                MethodVisitor soc = new SpecialOpcodeRemovingMV(an, ignoreFrames, acc, className, favDesc, fixLdcClass);
                LocalVariableManager lvs = new LocalVariableManager(acc, favDesc, soc, an, mv, generateExtraLVDebug);
                LinkedList<LocalVariableNode> lvsToVisit = new LinkedList<>();
                Type returnType = Type.getReturnType(favDesc);
                lvs.setPrimitiveArrayAnalyzer(new PrimitiveArrayAnalyzer(returnType));
                GeneratorAdapter ga = new GeneratorAdapter(lvs, acc, "getAddr$$FAV", favDesc);
                LabelNode start = new LabelNode(new Label());
                LabelNode end = new LabelNode(new Label());
                ga.visitCode();
                ga.visitLabel(start.getLabel());

                if((acc & Opcodes.ACC_STATIC) == 0) {
                    ga.visitVarInsn(ALOAD, 0);
                    lvsToVisit.add(new LocalVariableNode("this", "L" + className + ";", null, start, end, 0));
                }

                ga.visitVarInsn(ALOAD, 0);
                if(Configuration.IS_THIRD_PARTY_PROTO) {
                	ga.visitFieldInsn(Opcodes.GETFIELD, className, "addr", "Ljava/net/InetSocketAddress;");
                } else {
                	ga.visitFieldInsn(Opcodes.GETFIELD, className, "addr", "Lorg/apache/hadoop/hbase/net/Address;");
                    ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/apache/hadoop/hbase/net/Address", "toSocketAddress", "()Ljava/net/InetSocketAddress;");
                }

                ga.visitLabel(end.getLabel());
                ga.returnValue();
                for(LocalVariableNode n : lvsToVisit) {
                    n.accept(ga);
                }
                ga.visitMaxs(0, 0);
                ga.visitEnd();
            }
        }
        if(Configuration.USE_FAV && (Configuration.FOR_ZK || Configuration.ZK_CLI)) {
        	if(this.className.equals("org/apache/jute/BinaryOutputArchive")){
            	MethodVisitor mv;
                int acc = Opcodes.ACC_PUBLIC;
                String favDesc = "()Ljava/net/InetAddress;";
                mv = super.visitMethod(acc, "getAddr$$FAV", favDesc, null, null);
                NeverNullArgAnalyzerAdapter an = new NeverNullArgAnalyzerAdapter(className, acc, "getAddr$$FAV", favDesc, mv);
                MethodVisitor soc = new SpecialOpcodeRemovingMV(an, ignoreFrames, acc, className, favDesc, fixLdcClass);
                LocalVariableManager lvs = new LocalVariableManager(acc, favDesc, soc, an, mv, generateExtraLVDebug);
                LinkedList<LocalVariableNode> lvsToVisit = new LinkedList<>();
                Type returnType = Type.getReturnType(favDesc);
                lvs.setPrimitiveArrayAnalyzer(new PrimitiveArrayAnalyzer(returnType));
                GeneratorAdapter ga = new GeneratorAdapter(lvs, acc, "getAddr$$FAV", favDesc);
                LabelNode start = new LabelNode(new Label());
                LabelNode end = new LabelNode(new Label());
                ga.visitCode();
                ga.visitLabel(start.getLabel());

                if((acc & Opcodes.ACC_STATIC) == 0) {
                    ga.visitVarInsn(ALOAD, 0);
                    lvsToVisit.add(new LocalVariableNode("this", "L" + className + ";", null, start, end, 0));
                }

                ga.visitVarInsn(ALOAD, 0);
                ga.visitFieldInsn(Opcodes.GETFIELD, className, "out", "Ljava/io/DataOutput;");
                ga.visitTypeInsn(Opcodes.CHECKCAST, "java/io/DataOutputStream");
                ga.visitFieldInsn(Opcodes.GETFIELD, "java/io/DataOutputStream", "favAddr", "Ljava/net/InetAddress;");

                ga.visitLabel(end.getLabel());
                ga.returnValue();
                for(LocalVariableNode n : lvsToVisit) {
                    n.accept(ga);
                }
                ga.visitMaxs(0, 0);
                ga.visitEnd();
            }
        }
        if(Configuration.USE_FAV && (Configuration.FOR_ZK || Configuration.ZK_CLI)) {
        	if(this.className.equals("org/apache/jute/BinaryInputArchive")){
            	MethodVisitor mv;
                int acc = Opcodes.ACC_PUBLIC;
                String favDesc = "()Ljava/net/InetAddress;";
                mv = super.visitMethod(acc, "getAddr$$FAV", favDesc, null, null);
                NeverNullArgAnalyzerAdapter an = new NeverNullArgAnalyzerAdapter(className, acc, "getAddr$$FAV", favDesc, mv);
                MethodVisitor soc = new SpecialOpcodeRemovingMV(an, ignoreFrames, acc, className, favDesc, fixLdcClass);
                LocalVariableManager lvs = new LocalVariableManager(acc, favDesc, soc, an, mv, generateExtraLVDebug);
                LinkedList<LocalVariableNode> lvsToVisit = new LinkedList<>();
                Type returnType = Type.getReturnType(favDesc);
                lvs.setPrimitiveArrayAnalyzer(new PrimitiveArrayAnalyzer(returnType));
                GeneratorAdapter ga = new GeneratorAdapter(lvs, acc, "getAddr$$FAV", favDesc);
                LabelNode start = new LabelNode(new Label());
                LabelNode end = new LabelNode(new Label());
                ga.visitCode();
                ga.visitLabel(start.getLabel());

                if((acc & Opcodes.ACC_STATIC) == 0) {
                    ga.visitVarInsn(ALOAD, 0);
                    lvsToVisit.add(new LocalVariableNode("this", "L" + className + ";", null, start, end, 0));
                }

                ga.visitVarInsn(ALOAD, 0);
                ga.visitFieldInsn(Opcodes.GETFIELD, className, "in", "Ljava/io/DataInput;");
                ga.visitTypeInsn(Opcodes.CHECKCAST, "java/io/DataInputStream");
                ga.visitFieldInsn(Opcodes.GETFIELD, "java/io/DataInputStream", "favAddr", "Ljava/net/InetAddress;");

                ga.visitLabel(end.getLabel());
                ga.returnValue();
                for(LocalVariableNode n : lvsToVisit) {
                    n.accept(ga);
                }
                ga.visitMaxs(0, 0);
                ga.visitEnd();
            }
        }
        //favtrigger: end

        //favtrigger: add get path method FileInputStream & FileOutputStream
        if(this.className.equals("java/io/FileInputStream") || this.className.equals("java/io/FileOutputStream") || this.className.equals("java/io/RandomAccessFile")){
        	MethodVisitor mv;
            int acc = Opcodes.ACC_PUBLIC;
            mv = super.visitMethod(acc, TaintUtils.FAV_GETFILESTREAMPATH_MT, "()Ljava/lang/String;", null, null);
            NeverNullArgAnalyzerAdapter an = new NeverNullArgAnalyzerAdapter(className, acc, TaintUtils.FAV_FILEDESCRIPTOR_MT, "()Ljava/lang/String;", mv);
            MethodVisitor soc = new SpecialOpcodeRemovingMV(an, ignoreFrames, acc, className, "()Ljava/lang/String;", fixLdcClass);
            LocalVariableManager lvs = new LocalVariableManager(acc, "()Ljava/lang/String;", soc, an, mv, generateExtraLVDebug);
            LinkedList<LocalVariableNode> lvsToVisit = new LinkedList<>();
            Type returnType = Type.getReturnType("()Ljava/lang/String;");
            lvs.setPrimitiveArrayAnalyzer(new PrimitiveArrayAnalyzer(returnType));
            GeneratorAdapter ga = new GeneratorAdapter(lvs, acc, TaintUtils.FAV_GETFILESTREAMPATH_MT, "()Ljava/lang/String;");
            LabelNode start = new LabelNode(new Label());
            LabelNode end = new LabelNode(new Label());
            ga.visitCode();
            ga.visitLabel(start.getLabel());

            if((acc & Opcodes.ACC_STATIC) == 0) {
                ga.visitVarInsn(ALOAD, 0);
                lvsToVisit.add(new LocalVariableNode("this", "L" + className + ";", null, start, end, 0));
            }

            ga.visitVarInsn(ALOAD, 0);
            ga.visitFieldInsn(Opcodes.GETFIELD, className, "path", "Ljava/lang/String;");
            int path = lvs.getTmpLV();
            ga.visitVarInsn(Opcodes.ASTORE, path);
            ga.visitVarInsn(Opcodes.ALOAD, path);
            Label nonNull = new Label();
            ga.visitJumpInsn(Opcodes.IFNONNULL, nonNull);
            ga.visitLdcInsn("");
            ga.visitVarInsn(Opcodes.ASTORE, path);
            ga.visitLabel(nonNull);
            ga.visitVarInsn(ALOAD, path);

            lvs.freeTmpLV(path);

            ga.visitLabel(end.getLabel());
            ga.returnValue();
            for(LocalVariableNode n : lvsToVisit) {
                n.accept(ga);
            }
            ga.visitMaxs(0, 0);
            ga.visitEnd();
        }
        //favtrigger: end

        //favtrigger: add check source method for FileDescriptor
        if(this.className.equals("java/io/FileDescriptor")){
            MethodVisitor mv;
            int acc = Opcodes.ACC_PUBLIC;
            mv = super.visitMethod(acc, TaintUtils.FAV_FILEDESCRIPTOR_MT, "()Ljava/lang/String;", null, null);
            NeverNullArgAnalyzerAdapter an = new NeverNullArgAnalyzerAdapter(className, acc, TaintUtils.FAV_FILEDESCRIPTOR_MT, "()Ljava/lang/String;", mv);
            MethodVisitor soc = new SpecialOpcodeRemovingMV(an, ignoreFrames, acc, className, "()Ljava/lang/String;", fixLdcClass);
            LocalVariableManager lvs = new LocalVariableManager(acc, "()Ljava/lang/String;", soc, an, mv, generateExtraLVDebug);
            LinkedList<LocalVariableNode> lvsToVisit = new LinkedList<>();
            Type returnType = Type.getReturnType("()Ljava/lang/String;");
            lvs.setPrimitiveArrayAnalyzer(new PrimitiveArrayAnalyzer(returnType));
            GeneratorAdapter ga = new GeneratorAdapter(lvs, acc, TaintUtils.FAV_FILEDESCRIPTOR_MT, "()Ljava/lang/String;");
            LabelNode start = new LabelNode(new Label());
            LabelNode end = new LabelNode(new Label());
            ga.visitCode();
            ga.visitLabel(start.getLabel());

            if((acc & Opcodes.ACC_STATIC) == 0) {
                ga.visitVarInsn(ALOAD, 0);
                lvsToVisit.add(new LocalVariableNode("this", "L" + className + ";", null, start, end, 0));
            }

            ga.visitVarInsn(ALOAD, 0);
            ga.visitFieldInsn(Opcodes.GETFIELD, className, "parent", Type.getDescriptor(Closeable.class));
            ga.visitTypeInsn(Opcodes.INSTANCEOF, Type.getInternalName(FileOutputStream.class));
            Label notFileOut = new Label();
            Label done = new Label();
            ga.visitJumpInsn(Opcodes.IFEQ, notFileOut);
            //ga.visitLdcInsn("fileout");
            /*
            ga.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
            ga.visitInsn(Opcodes.DUP);
            ga.visitFieldInsn(Opcodes.GETSTATIC, Type.getInternalName(LinkType.class), "FILE", Type.getDescriptor(LinkType.class));
            ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(LinkType.class), "toString", "()Ljava/lang/String;", false);
            ga.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
            ga.visitLdcInsn(":");
            ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder",
            "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
            ga.visitVarInsn(ALOAD, 0);
            ga.visitFieldInsn(Opcodes.GETFIELD, className, "parent", Type.getDescriptor(Closeable.class));
            ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(FileOutputStream.class), TaintUtils.FAV_GETFILESTREAMPATH_MT, "()Ljava/lang/String;", false);
            ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
            ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
            ga.visitJumpInsn(Opcodes.GOTO, done);
            */
            ga.visitVarInsn(ALOAD, 0);
            ga.visitFieldInsn(Opcodes.GETFIELD, className, "parent", Type.getDescriptor(Closeable.class));
            ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(FileOutputStream.class), TaintUtils.FAV_GETFILESTREAMPATH_MT, "()Ljava/lang/String;", false);
            ga.visitJumpInsn(Opcodes.GOTO, done);

            ga.visitLabel(notFileOut);
            Label notFileIn = new Label();
            ga.visitVarInsn(ALOAD, 0);
            ga.visitFieldInsn(Opcodes.GETFIELD, className, "parent", Type.getDescriptor(Closeable.class));
            ga.visitTypeInsn(Opcodes.INSTANCEOF, "java/io/FileInputStream");
            ga.visitJumpInsn(Opcodes.IFEQ, notFileIn);
            //ga.visitLdcInsn("filein");
            /*
            ga.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
            ga.visitInsn(Opcodes.DUP);
            ga.visitFieldInsn(Opcodes.GETSTATIC, Type.getInternalName(LinkType.class), "FILE", Type.getDescriptor(LinkType.class));
            ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(LinkType.class), "toString", "()Ljava/lang/String;", false);
            ga.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
            ga.visitLdcInsn(":");
            ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
            ga.visitVarInsn(ALOAD, 0);
            ga.visitFieldInsn(Opcodes.GETFIELD, className, "parent", Type.getDescriptor(Closeable.class));
            ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/FileInputStream", TaintUtils.FAV_GETFILESTREAMPATH_MT, "()Ljava/lang/String;", false);
            ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
            ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
            ga.visitJumpInsn(Opcodes.GOTO, done);
            */
            ga.visitVarInsn(ALOAD, 0);
            ga.visitFieldInsn(Opcodes.GETFIELD, className, "parent", Type.getDescriptor(Closeable.class));
            ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/FileInputStream", TaintUtils.FAV_GETFILESTREAMPATH_MT, "()Ljava/lang/String;", false);
            ga.visitJumpInsn(Opcodes.GOTO, done);

            ga.visitLabel(notFileIn);
            Label notRandomAcc = new Label();
            ga.visitVarInsn(ALOAD, 0);
            ga.visitFieldInsn(Opcodes.GETFIELD, className, "parent", Type.getDescriptor(Closeable.class));
            ga.visitTypeInsn(Opcodes.INSTANCEOF, "java/io/RandomAccessFile");
            ga.visitJumpInsn(Opcodes.IFEQ, notRandomAcc);
            //ga.visitLdcInsn("filein");
            /*
            ga.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
            ga.visitInsn(Opcodes.DUP);
            ga.visitFieldInsn(Opcodes.GETSTATIC, Type.getInternalName(LinkType.class), "FILE", Type.getDescriptor(LinkType.class));
            ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(LinkType.class), "toString", "()Ljava/lang/String;", false);
            ga.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
            ga.visitLdcInsn(":");
            ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
            ga.visitVarInsn(ALOAD, 0);
            ga.visitFieldInsn(Opcodes.GETFIELD, className, "parent", Type.getDescriptor(Closeable.class));
            ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/RandomAccessFile", TaintUtils.FAV_GETFILESTREAMPATH_MT, "()Ljava/lang/String;", false);
            ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
            ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
            ga.visitJumpInsn(Opcodes.GOTO, done);
            */
            ga.visitVarInsn(ALOAD, 0);
            ga.visitFieldInsn(Opcodes.GETFIELD, className, "parent", Type.getDescriptor(Closeable.class));
            ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/RandomAccessFile", TaintUtils.FAV_GETFILESTREAMPATH_MT, "()Ljava/lang/String;", false);
            ga.visitJumpInsn(Opcodes.GOTO, done);

            ga.visitLabel(notRandomAcc);
            Label notDatagramSocket = new Label();
            ga.visitVarInsn(ALOAD, 0);
            ga.visitFieldInsn(Opcodes.GETFIELD, className, "parent", Type.getDescriptor(Closeable.class));
            ga.visitTypeInsn(Opcodes.INSTANCEOF, "java/net/DatagramSocket");
            ga.visitJumpInsn(Opcodes.IFEQ, notDatagramSocket);
            ga.visitLdcInsn(FAVPathType.FAVMSG.toString()+":DatagramSocket");
            ga.visitJumpInsn(Opcodes.GOTO, done);

            ga.visitLabel(notDatagramSocket);
            Label notServerSocket = new Label();
            ga.visitVarInsn(ALOAD, 0);
            ga.visitFieldInsn(Opcodes.GETFIELD, className, "parent", Type.getDescriptor(Closeable.class));
            ga.visitTypeInsn(Opcodes.INSTANCEOF, "java/net/ServerSocket");
            ga.visitJumpInsn(Opcodes.IFEQ, notServerSocket);
            ga.visitLdcInsn(FAVPathType.FAVMSG.toString()+":ServerSocket");
            ga.visitJumpInsn(Opcodes.GOTO, done);

            ga.visitLabel(notServerSocket);
            Label notSocket = new Label();
            ga.visitVarInsn(ALOAD, 0);
            ga.visitFieldInsn(Opcodes.GETFIELD, className, "parent", Type.getDescriptor(Closeable.class));
            ga.visitTypeInsn(Opcodes.INSTANCEOF, "java/net/Socket");
            ga.visitJumpInsn(Opcodes.IFEQ, notSocket);
            ga.visitLdcInsn(FAVPathType.FAVMSG.toString()+":Socket");
            ga.visitJumpInsn(Opcodes.GOTO, done);

            ga.visitLabel(notSocket);
            //ga.visitLdcInsn("other");
            ga.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
            ga.visitInsn(Opcodes.DUP);
            ga.visitFieldInsn(Opcodes.GETSTATIC, Type.getInternalName(LinkType.class), "OTHER", Type.getDescriptor(LinkType.class));
            ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(LinkType.class), "toString", "()Ljava/lang/String;", false);
            ga.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
            ga.visitLdcInsn(":");
            ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
            ga.visitVarInsn(ALOAD, 0);
            ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false);
            ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
            ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder",
            "toString", "()Ljava/lang/String;", false);

            ga.visitLabel(done);

            ga.visitLabel(end.getLabel());
            ga.returnValue();
            for(LocalVariableNode n : lvsToVisit) {
                n.accept(ga);
            }
            ga.visitMaxs(0, 0);
            ga.visitEnd();
        }
        //favtrigger: end

        //For weird lambdas
        for(MethodNode m : methodsToAddNameOnlyWrappersFor) {
            if((m.access & Opcodes.ACC_ABSTRACT) == 0 && !m.name.contains("<")) {
                String[] exceptions = new String[m.exceptions.size()];
                exceptions = m.exceptions.toArray(exceptions);

                boolean isVirtual = (m.access & Opcodes.ACC_STATIC) == 0;
                String newDesc = m.desc;
                if(isVirtual) {
                    newDesc = "(" + Configuration.TAINT_TAG_DESC + newDesc.substring(1);
                }
                MethodVisitor mv = super.visitMethod(m.access, m.name + TaintUtils.METHOD_SUFFIX, newDesc, m.signature, exceptions);
                GeneratorAdapter ga = new GeneratorAdapter(mv, m.access, m.name + TaintUtils.METHOD_SUFFIX, newDesc);
                MethodVisitor reIndexer = new MethodVisitor(Configuration.ASM_VERSION, ga) {
                    @Override
                    public void visitVarInsn(int opcode, int var) {
                        super.visitVarInsn(opcode, (isVirtual && var > 0 ? 1 : 0) + var);
                    }
                };
                m.accept(reIndexer);

            }

        }
        for(MethodNode m : methodsToAddUnWrappersFor) {
            if((m.access & Opcodes.ACC_ABSTRACT) == 0 && !m.name.contains("<")) {
                String[] exceptions = new String[m.exceptions.size()];
                exceptions = m.exceptions.toArray(exceptions);

                LinkedList<Type> newArgs = new LinkedList<>();
                for(Type t : Type.getArgumentTypes(m.desc)) {
                    if(TaintUtils.isPrimitiveOrPrimitiveArrayType(t)) {
                        newArgs.removeLast();
                    }
                    if(t.getDescriptor().startsWith("Ledu/columbia/cs/psl/phosphor/struct/Lazy")) {
                        newArgs.add(MultiDTaintedArray.getPrimitiveTypeForWrapper(t.getInternalName()));
                    } else {
                        newArgs.add(t);
                    }
                }
                Type returnType = Type.getReturnType(m.desc);
                String t = returnType.getDescriptor();
                Type returnTypeToHackOnLambda = null;
                if(returnType.getSort() == Type.VOID) {
                    //Check to see if we are one of those annoying lambdas with a void return but pre-allocated return obj passed
                    if(!newArgs.isEmpty() && newArgs.getLast().getDescriptor().startsWith("Ledu/columbia/cs/psl/phosphor/struct/Tainted")) {
                        returnTypeToHackOnLambda = newArgs.removeLast();
                    }
                }
                if(returnType.getDescriptor().startsWith("Ledu/columbia/cs/psl/phosphor/struct/Tainted")) {
                    Type lastArg = newArgs.removeLast();
                    if(lastArg.getSort() == Type.OBJECT){
                        newArgs.removeLast(); //what we removed above was just the erased return type...
                    }
                    //Need to change return type...
                    if(returnType.getDescriptor().contains("edu/columbia/cs/psl/phosphor/struct/Tainted")) {
                        t = returnType.getDescriptor().replace("Ledu/columbia/cs/psl/phosphor/struct/Tainted", "");
                        t = t.replace("WithIntTag", "");
                        t = t.replace("WithObjTag", "");
                    }
                    t = t.replace(";", "").replace("Int", "I")
                            .replace("Byte", "B")
                            .replace("Short", "S")
                            .replace("Long", "J")
                            .replace("Boolean", "Z")
                            .replace("Float", "F")
                            .replace("Double", "D");
                    if(t.equals("Reference")) {
                        t = "Ljava/lang/Object;";
                    }

                }
                if((Configuration.IMPLICIT_TRACKING || Configuration.IMPLICIT_HEADERS_NO_TRACKING) && !newArgs.isEmpty()) {
                    newArgs.removeLast(); //remove control taint tag
                }
                StringBuilder newDesc = new StringBuilder("(");
                for(Type _t : newArgs) {
                    if(!_t.getDescriptor().equals(Configuration.TAINT_TAG_DESC)) {
                        newDesc.append(_t.getDescriptor());
                    }
                }
                newDesc.append(")");
                newDesc.append(t);

                MethodNode z = new MethodNode(m.access | Opcodes.ACC_MODULE, m.name.replace(TaintUtils.METHOD_SUFFIX, ""), newDesc.toString(), m.signature, exceptions);
                methodsToAddWrappersFor.add(z);
                methodsToAddLambdaUnWrappersFor.add(z);
                if(returnTypeToHackOnLambda != null) {
                    methodsToAddWrappersForWithReturnType.put(z, returnTypeToHackOnLambda);
                }
            }
            if((m.access & Opcodes.ACC_ABSTRACT) == 0 && !m.name.contains("<")) {
                String[] exceptions = new String[m.exceptions.size()];
                exceptions = m.exceptions.toArray(exceptions);

                LinkedList<Type> newArgs = new LinkedList<>();
                for(Type t : Type.getArgumentTypes(m.desc)) {
                    if(t.getDescriptor().startsWith("Ledu/columbia/cs/psl/phosphor/struct/Lazy")) {
                        newArgs.add(MultiDTaintedArray.getPrimitiveTypeForWrapper(t.getInternalName()));
                    } else if(!t.getDescriptor().equals(Configuration.TAINT_TAG_DESC)) {
                        newArgs.add(t);
                    }
                }
                Type returnType = Type.getReturnType(m.desc);
                String t = returnType.getDescriptor();
                if(returnType.getDescriptor().startsWith("Ledu/columbia/cs/psl/phosphor/struct/Tainted")) {
                    newArgs.removeLast();
                    //Need to change return type...
                    if(returnType.getDescriptor().contains("edu/columbia/cs/psl/phosphor/struct/Tainted")) {
                        t = returnType.getDescriptor().replace("Ledu/columbia/cs/psl/phosphor/struct/Tainted", "");
                        t = t.replace("WithIntTag", "");
                        t = t.replace("WithObjTag", "");
                    }
                    t = t.replace(";", "").replace("Int", "I")
                            .replace("Byte", "B")
                            .replace("Short", "S")
                            .replace("Long", "J")
                            .replace("Boolean", "Z")
                            .replace("Float", "F")
                            .replace("Double", "D");
                    if(t.equals("Reference")) {
                        t = "Ljava/lang/Object;";
                    }
                }
                if(returnType.getSort() == Type.VOID) {
                    //Check to see if we are one of those annying lambdas with a void return but pre-allocated return obj passed
                    if(!newArgs.isEmpty() && newArgs.getLast().getDescriptor().startsWith("Ledu/columbia/cs/psl/phosphor/struct/Tainted")) {
                        newArgs.removeLast();
                    }
                }
                if((Configuration.IMPLICIT_TRACKING || Configuration.IMPLICIT_HEADERS_NO_TRACKING) && !newArgs.isEmpty()) {
                    newArgs.removeLast();//remove ControlFlowStack
                }
                String newDesc = "(";
                for(Type _t : newArgs) {
                    newDesc += _t.getDescriptor();
                }
                newDesc += ")";
                newDesc += t;

                boolean isStatic = (Opcodes.ACC_STATIC & m.access) != 0;
                newDesc = TaintUtils.remapMethodDescAndIncludeReturnHolder(!isStatic, newDesc);
                if(newDesc.equals(m.desc)) {
                    continue;
                }
                MethodVisitor mv = super.visitMethod(m.access, m.name, newDesc, m.signature, exceptions);
                GeneratorAdapter ga = new GeneratorAdapter(mv, m.access, m.name, newDesc);

                Label startLabel = new Label();
                ga.visitCode();
                ga.visitLabel(startLabel);
                ga.visitLineNumber(0, startLabel);

                switch(Type.getReturnType(newDesc).getSort()) {
                    case Type.INT:
                    case Type.SHORT:
                    case Type.BOOLEAN:
                    case Type.BYTE:
                    case Type.CHAR:
                        ga.visitInsn(Opcodes.ICONST_0);
                        break;
                    case Type.ARRAY:
                    case Type.OBJECT:
                        ga.visitInsn(Opcodes.ACONST_NULL);
                        break;
                    case Type.LONG:
                        ga.visitInsn(Opcodes.LCONST_0);
                        break;
                    case Type.DOUBLE:
                        ga.visitInsn(Opcodes.DCONST_0);
                        break;
                    case Type.FLOAT:
                        ga.visitInsn(Opcodes.FCONST_0);
                        break;
                }
                Label endLabel = new Label();
                ga.visitLabel(endLabel);
                ga.returnValue();

                for(Object o : m.localVariables) {
                    LocalVariableNode n = (LocalVariableNode) o;
                    ga.visitLocalVariable(n.name, n.desc, n.signature, startLabel, endLabel, n.index);
                }
                ga.visitMaxs(0, 0);
                ga.visitEnd();
            }
        }

        if(!goLightOnGeneratedStuff) {
            for(MethodNode m : methodsToAddWrappersFor) {
                if((m.access & Opcodes.ACC_NATIVE) == 0 && !isUninstMethods) {
                    if((m.access & Opcodes.ACC_ABSTRACT) == 0) {
                        //not native
                        MethodNode fullMethod = forMore.get(m);

                        Type origReturn = Type.getReturnType(m.desc);
                        Type newReturn = TaintUtils.getContainerReturnType(origReturn);

                        Type returnTypeToHackOnLambda = methodsToAddWrappersForWithReturnType.get(m);
                        boolean needToPrealloc = TaintUtils.isPreAllocReturnType(m.desc) || returnTypeToHackOnLambda != null;
                        boolean isStatic = (Opcodes.ACC_STATIC & m.access) != 0;
                        boolean useSuffixName = !TaintUtils.remapMethodDescAndIncludeReturnHolder(!isStatic, m.desc).equals(m.desc) || Type.getReturnType(m.desc).getDescriptor().equals("Ljava/lang/Object;") || methodsToAddLambdaUnWrappersFor.contains(m);
                        String[] exceptions = new String[m.exceptions.size()];
                        exceptions = m.exceptions.toArray(exceptions);

                        boolean useInvokeVirtual = (m.access & Opcodes.ACC_MODULE) != 0 || (isLambda && (m.access & Opcodes.ACC_STATIC) == 0);
                        m.access = m.access & ~Opcodes.ACC_MODULE;
                        MethodVisitor mv = super.visitMethod(m.access, m.name, m.desc, m.signature, exceptions);
                        mv = new TaintTagFieldCastMV(mv, m.name);

                        if(fullMethod != null) {
                            visitAnnotations(mv, fullMethod);
                        }

                        MethodVisitor soc = new SpecialOpcodeRemovingMV(mv, ignoreFrames, m.access, className, m.desc, fixLdcClass);
                        NeverNullArgAnalyzerAdapter an = new NeverNullArgAnalyzerAdapter(className, m.access, m.name, m.desc, soc);
                        LocalVariableManager lvs = new LocalVariableManager(m.access, m.desc, an, an, mv, generateExtraLVDebug);
                        lvs.setPrimitiveArrayAnalyzer(new PrimitiveArrayAnalyzer(returnTypeToHackOnLambda == null ? newReturn : returnTypeToHackOnLambda));
                        GeneratorAdapter ga = new GeneratorAdapter(lvs, m.access, m.name, m.desc);

                        Label startLabel = new Label();
                        ga.visitCode();
                        ga.visitLabel(startLabel);
                        ga.visitLineNumber(0, startLabel);

                        Type[] argTypes = Type.getArgumentTypes(m.desc);
                        int idx = 0;
                        if((m.access & Opcodes.ACC_STATIC) == 0) {
                            ga.visitVarInsn(ALOAD, 0);
                            idx++;
                        }

                        String newDesc = "(";
                        if(!isStatic) {
                            newDesc += Configuration.TAINT_TAG_DESC;
                            NEW_EMPTY_TAINT.delegateVisit(ga);
                        }
                        int nWrappers = 0;
                        String wrapperDesc = "";
                        for(Type t : argTypes) {
                            boolean loaded = false;
                            boolean needToBoxMultiD = false;
                            if(TaintUtils.isWrappedType(t)) {
                                if(t.getSort() == Type.ARRAY && t.getDimensions() == 1) {
                                    newDesc += TaintUtils.getWrapperType(t);
                                    ga.visitVarInsn(ALOAD, idx);
                                    TaintAdapter.createNewTaintArray(t.getDescriptor(), an, lvs, lvs);
                                    loaded = true;
                                } else {
                                    newDesc += TaintUtils.getWrapperType(t);
                                    needToBoxMultiD = true;
                                }
                            } else {
                                newDesc += t.getDescriptor();
                            }
                            if(TaintUtils.isWrappedTypeWithErasedType(t)) {
                                nWrappers++;
                                wrapperDesc += t.getDescriptor();
                            }
                            if(!loaded) {
                                ga.visitVarInsn(t.getOpcode(Opcodes.ILOAD), idx);
                            }
                            if(NATIVE_BOX_UNBOX && t.getSort() == Type.OBJECT && Instrumenter.isCollection(t.getInternalName())) {
                                ga.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(NativeHelper.class), "ensureIsBoxedObjTags", "(Ljava/util/Collection;)Ljava/util/Collection;", false);
                                ga.visitTypeInsn(Opcodes.CHECKCAST, t.getInternalName());
                            }
                            if(!TaintUtils.isWrappedType(t) && t.getDescriptor().endsWith("Ljava/lang/Object;") && !className.contains("MethodAccessorImpl") && !m.name.startsWith("invoke") && !className.contains("ConstructorAccessorImpl")) {
                                ga.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(MultiDTaintedArray.class), "boxIfNecessary", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                                ga.visitTypeInsn(Opcodes.CHECKCAST, t.getInternalName());
                            }
                            if(needToBoxMultiD) {
                                Label isDone = new Label();
                                ga.visitInsn(Opcodes.DUP);
                                ga.visitJumpInsn(Opcodes.IFNULL, isDone);
                                ga.visitIntInsn(Opcodes.BIPUSH, t.getElementType().getSort());
                                ga.visitIntInsn(Opcodes.BIPUSH, t.getDimensions());
                                ga.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(MultiDTaintedArrayWithObjTag.class), "initWithEmptyTaints", "([Ljava/lang/Object;II)Ljava/lang/Object;", false);
                                FrameNode fn = TaintAdapter.getCurrentFrameNode(an);
                                fn.stack.set(fn.stack.size() - 1, "java/lang/Object");
                                ga.visitLabel(isDone);
                                TaintAdapter.acceptFn(fn, lvs);
                                ga.visitTypeInsn(Opcodes.CHECKCAST, MultiDTaintedArray.getTypeForType(t).getInternalName());

                            }
                            if(TaintUtils.isShadowedType(t)) {
                                newDesc += Configuration.TAINT_TAG_DESC;
                                Configuration.taintTagFactory.generateEmptyTaint(ga);
                            }
                            idx += t.getSize();
                        }
                        if(TaintUtils.isErasedReturnType(Type.getReturnType(m.desc))) {
                            nWrappers++;
                            wrapperDesc += Type.getReturnType(m.desc).getDescriptor();
                        }
                        if(Configuration.IMPLICIT_TRACKING) {
                            newDesc += CONTROL_STACK_DESC;
                            controlFlowManager.visitCreateStack(ga, false);
                        } else if(Configuration.IMPLICIT_HEADERS_NO_TRACKING) {
                            newDesc += CONTROL_STACK_DESC;
                            controlFlowManager.visitCreateStack(ga, true);
                        }
                        if(needToPrealloc) {
                            newDesc += returnTypeToHackOnLambda == null ? newReturn.getDescriptor() : returnTypeToHackOnLambda.getDescriptor();
                            an.visitVarInsn(ALOAD, lvs.getPreAllocatedReturnTypeVar(returnTypeToHackOnLambda == null ? newReturn : returnTypeToHackOnLambda));
                        }
                        newDesc += wrapperDesc;
                        newDesc += ")" + newReturn.getDescriptor();

                        int opcode;
                        if(useInvokeVirtual) {
                            opcode = Opcodes.INVOKEVIRTUAL;
                        } else if((m.access & Opcodes.ACC_STATIC) == 0) {
                            opcode = Opcodes.INVOKESPECIAL;
                        } else {
                            opcode = Opcodes.INVOKESTATIC;
                        }
                        for(int i = 0; i < nWrappers; i++) {
                            ga.visitInsn(Opcodes.ACONST_NULL);
                        }
                        if(m.name.equals("<init>")) {
                            ga.visitMethodInsn(Opcodes.INVOKESPECIAL, className, m.name, newDesc, false);
                        } else {
                            ga.visitMethodInsn(opcode, className, m.name + (useSuffixName ? TaintUtils.METHOD_SUFFIX : ""), newDesc, false);
                        }
                        //unbox collections
                        idx = 0;
                        if((m.access & Opcodes.ACC_STATIC) == 0) {
                            idx++;
                        }

                        for(Type t : argTypes) {
                            if(NATIVE_BOX_UNBOX && t.getSort() == Type.OBJECT && Instrumenter.isCollection(t.getInternalName())) {
                                ga.visitVarInsn(t.getOpcode(Opcodes.ILOAD), idx);
                                ga.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(NativeHelper.class), "ensureIsUnBoxedObjTags", "(Ljava/util/Collection;)Ljava/util/Collection;", false);
                                ga.visitInsn(Opcodes.POP);
                            }
                            idx += t.getSize();
                        }
                        if(origReturn != newReturn) {
                            String taintType = TaintUtils.getShadowTaintType(origReturn.getDescriptor());
                            if(taintType != null) {
                                if(origReturn.getSort() == Type.OBJECT || origReturn.getSort() == Type.ARRAY) {
                                    ga.visitFieldInsn(Opcodes.GETFIELD, newReturn.getInternalName(), "val", "Ljava/lang/Object;");
                                    if(TaintUtils.isWrappedType(origReturn)) {
                                        Type wrapper = TaintUtils.getWrapperType(origReturn);
                                        ga.visitTypeInsn(Opcodes.CHECKCAST, wrapper.getInternalName());
                                        if(wrapper.getSort() == Type.ARRAY) {
                                            //multid
                                            ENSURE_UNBOXED.delegateVisit(ga);
                                            ga.visitTypeInsn(Opcodes.CHECKCAST, origReturn.getInternalName());
                                        } else {
                                            ga.visitMethodInsn(Opcodes.INVOKESTATIC, wrapper.getInternalName(), "unwrap", "(" + wrapper.getDescriptor() + ")" + TaintUtils.getUnwrappedType(wrapper).getDescriptor(), false);
                                            if(TaintUtils.getUnwrappedType(wrapper).getDescriptor() != origReturn.getDescriptor()) {
                                                ga.visitTypeInsn(Opcodes.CHECKCAST, origReturn.getInternalName());
                                            }
                                        }
                                    } else {
                                        if(origReturn.getInternalName().equals("java/lang/Object")) {
                                            ENSURE_UNBOXED.delegateVisit(ga);
                                        }
                                        ga.visitTypeInsn(Opcodes.CHECKCAST, origReturn.getInternalName());
                                    }
                                } else {
                                    ga.visitFieldInsn(Opcodes.GETFIELD, newReturn.getInternalName(), "val", origReturn.getDescriptor());
                                }
                            } else {
                                //Need to convert from [[WrapperForCArray to [[[C

                                Label isDone = new Label();
                                ga.visitInsn(Opcodes.DUP);
                                ga.visitJumpInsn(Opcodes.IFNULL, isDone);
                                ga.visitIntInsn(Opcodes.BIPUSH, origReturn.getElementType().getSort());
                                ga.visitIntInsn(Opcodes.BIPUSH, origReturn.getDimensions() - 1);
                                ga.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(MultiDTaintedArrayWithObjTag.class), "unboxVal", "(Ljava/lang/Object;II)Ljava/lang/Object;", false);
                                FrameNode fn = TaintAdapter.getCurrentFrameNode(an);
                                fn.stack.set(fn.stack.size() - 1, "java/lang/Object");
                                ga.visitLabel(isDone);
                                TaintAdapter.acceptFn(fn, lvs);
                                ga.visitTypeInsn(Opcodes.CHECKCAST, origReturn.getInternalName());

                            }
                        }
                        Label endLabel = new Label();
                        ga.visitLabel(endLabel);
                        ga.returnValue();
                        for(Object o : m.localVariables) {
                            LocalVariableNode n = (LocalVariableNode) o;
                            ga.visitLocalVariable(n.name, n.desc, n.signature, startLabel, endLabel, n.index);
                        }
                        ga.visitMaxs(0, 0);
                        ga.visitEnd();
                    } else {
                        String[] exceptions = new String[m.exceptions.size()];
                        exceptions = m.exceptions.toArray(exceptions);
                        MethodNode fullMethod = forMore.get(m);

                        MethodVisitor mv = super.visitMethod(m.access, m.name, m.desc, m.signature, exceptions);
                        visitAnnotations(mv, fullMethod);
                        m.accept(mv);
                    }
                } else {
                    // Generate wrapper for native method - a native wrapper
                    generateNativeWrapper(m, m.name, false);
                    if(className.equals("sun/misc/Unsafe")) {
                        generateNativeWrapper(m, m.name, true);
                    }
                }
            }
        }
        superMethodsToOverride.remove("wait(JI)V");
        superMethodsToOverride.remove("wait(J)V");
        superMethodsToOverride.remove("wait()V");
        superMethodsToOverride.remove("notify()V");
        superMethodsToOverride.remove("notifyAll()V");
        for(Method m : superMethodsToOverride.values()) {
            int acc = Opcodes.ACC_PUBLIC;
            if(Modifier.isProtected(m.getModifiers()) && isInterface) {
                continue;
            } else if(Modifier.isPrivate(m.getModifiers())) {
                continue;
            }
            if(Modifier.isStatic(m.getModifiers())) {
                acc = acc | Opcodes.ACC_STATIC;
            }
            if(isInterface) {
                acc = acc | Opcodes.ACC_ABSTRACT;
            } else {
                acc = acc & ~Opcodes.ACC_ABSTRACT;
            }
            MethodNode mn = new MethodNode(Configuration.ASM_VERSION, acc, m.getName(), Type.getMethodDescriptor(m), null, null);
            generateNativeWrapper(mn, mn.name, false);
        }

        super.visitEnd();
    }

    private void visitAnnotations(MethodVisitor mv, MethodNode fullMethod) {
        if(fullMethod.annotationDefault != null) {
            AnnotationVisitor av = mv.visitAnnotationDefault();
            acceptAnnotationRaw(av, null, fullMethod.annotationDefault);
            av.visitEnd();
        }
        if(fullMethod.visibleAnnotations != null) {
            for(Object o : fullMethod.visibleAnnotations) {
                AnnotationNode an = (AnnotationNode) o;
                an.accept(mv.visitAnnotation(an.desc, true));
            }
        }
        if(fullMethod.invisibleAnnotations != null) {
            for(Object o : fullMethod.invisibleAnnotations) {
                AnnotationNode an = (AnnotationNode) o;
                an.accept(mv.visitAnnotation(an.desc, false));
            }
        }
        if(fullMethod.visibleTypeAnnotations != null) {
            for(Object o : fullMethod.visibleTypeAnnotations) {
                TypeAnnotationNode an = (TypeAnnotationNode) o;
                an.accept(mv.visitTypeAnnotation(an.typeRef, an.typePath, an.desc, true));
            }
        }
        if(fullMethod.invisibleTypeAnnotations != null) {
            for(Object o : fullMethod.invisibleTypeAnnotations) {
                TypeAnnotationNode an = (TypeAnnotationNode) o;
                an.accept(mv.visitTypeAnnotation(an.typeRef, an.typePath, an.desc, false));
            }
        }
        if(fullMethod.parameters != null) {
            for(Object o : fullMethod.parameters) {
                ParameterNode pn = (ParameterNode) o;
                pn.accept(mv);
            }
        }
        if(fullMethod.visibleParameterAnnotations != null) {
            for(int i = 0; i < fullMethod.visibleParameterAnnotations.length; i++) {
                if(fullMethod.visibleParameterAnnotations[i] != null) {
                    for(Object o : fullMethod.visibleParameterAnnotations[i]) {
                        AnnotationNode an = (AnnotationNode) o;
                        an.accept(mv.visitParameterAnnotation(i, an.desc, true));
                    }
                }
            }
        }
        if(fullMethod.invisibleParameterAnnotations != null) {
            for(int i = 0; i < fullMethod.invisibleParameterAnnotations.length; i++) {
                if(fullMethod.invisibleParameterAnnotations[i] != null) {
                    for(Object o : fullMethod.invisibleParameterAnnotations[i]) {
                        AnnotationNode an = (AnnotationNode) o;
                        an.accept(mv.visitParameterAnnotation(i, an.desc, false));
                    }
                }
            }
        }
    }

    private void generateNativeWrapper(MethodNode m, String methodNameToCall, boolean skipUnboxing) {
        String[] exceptions = new String[m.exceptions.size()];
        exceptions = m.exceptions.toArray(exceptions);
        Type[] argTypes = Type.getArgumentTypes(m.desc);

        boolean isPreAllocReturnType = TaintUtils.isPreAllocReturnType(m.desc);
        java.lang.StringBuilder newDesc = new java.lang.StringBuilder("(");
        LinkedList<LocalVariableNode> lvsToVisit = new LinkedList<>();
        LabelNode start = new LabelNode(new Label());
        LabelNode end = new LabelNode(new Label());
        boolean isStatic = ((Opcodes.ACC_STATIC) & m.access) != 0;
        if(!isStatic) {
            newDesc.append(Configuration.TAINT_TAG_DESC);
        }
        StringBuilder wrapped = new StringBuilder();
        for(Type t : argTypes) {
            if(TaintUtils.isWrappedType(t)) {
                newDesc.append(TaintUtils.getWrapperType(t));
            } else {
                newDesc.append(t.getDescriptor());
            }
            if(TaintUtils.isShadowedType(t)) {
                newDesc.append(TaintUtils.getShadowTaintType(t.getDescriptor()));
            }
            if(TaintUtils.isWrappedTypeWithErasedType(t)) {
                wrapped.append(t.getDescriptor());
            }
        }
        Type origReturn = Type.getReturnType(m.desc);
        if(TaintUtils.isErasedReturnType(origReturn)){
            wrapped.append(origReturn.getDescriptor());
        }
        Type newReturn = TaintUtils.getContainerReturnType(origReturn);
        if((Configuration.IMPLICIT_HEADERS_NO_TRACKING || Configuration.IMPLICIT_TRACKING)) {
            newDesc.append(CONTROL_STACK_DESC);
        }
        if(isPreAllocReturnType) {
            newDesc.append(newReturn.getDescriptor());
        }
        newDesc.append(wrapped.toString());
        newDesc.append(")").append(newReturn.getDescriptor());

        MethodVisitor mv;
        int acc = m.access & ~Opcodes.ACC_NATIVE;
        boolean isInterfaceMethod = isInterface;
        if(isInterfaceMethod && forMore.get(m) != null && forMore.get(m).instructions.size() > 0){
            isInterfaceMethod = false;
        }
        if (!isInterfaceMethod) {
            acc = acc & ~Opcodes.ACC_ABSTRACT;
        }
        if (m.name.equals("<init>")) {
            mv = super.visitMethod(acc, m.name, newDesc.toString(), m.signature, exceptions);
        } else {
            mv = super.visitMethod(acc, m.name + TaintUtils.METHOD_SUFFIX + (skipUnboxing ? "$$NOUNBOX" : ""), newDesc.toString(), m.signature, exceptions);
        }
        NeverNullArgAnalyzerAdapter an = new NeverNullArgAnalyzerAdapter(className, m.access, m.name, newDesc.toString(), mv);
        MethodVisitor soc = new SpecialOpcodeRemovingMV(an, ignoreFrames, m.access, className, newDesc.toString(), fixLdcClass);
        LocalVariableManager lvs = new LocalVariableManager(acc, newDesc.toString(), soc, an, mv, generateExtraLVDebug);
        lvs.setPrimitiveArrayAnalyzer(new PrimitiveArrayAnalyzer(newReturn));
        GeneratorAdapter ga = new GeneratorAdapter(lvs, acc, m.name + TaintUtils.METHOD_SUFFIX, newDesc.toString());
        if(isInterfaceMethod) {
            ga.visitEnd();
            return;
        }
        ga.visitCode();
        ga.visitLabel(start.getLabel());
        if((Configuration.IMPLICIT_HEADERS_NO_TRACKING || Configuration.IMPLICIT_TRACKING)) {
            Type[] instArgTypes = ga.getArgumentTypes();
            for(int i = 0; i < instArgTypes.length; i++) {
                if(instArgTypes[i].equals(CONTROL_STACK_TYPE)) {
                    ga.loadArg(i);
                    CONTROL_STACK_UNINSTRUMENTED_WRAPPER.delegateVisit(ga);
                    break;
                }
            }
        }
        if(isLambda) {
            if(m.name.equals("equals")) {
                int retVar = (Configuration.IMPLICIT_TRACKING || Configuration.IMPLICIT_HEADERS_NO_TRACKING ? 5 : 4);
                ga.visitVarInsn(ALOAD, retVar);
                NEW_EMPTY_TAINT.delegateVisit(ga);
                ga.visitFieldInsn(Opcodes.PUTFIELD, newReturn.getInternalName(), "taint", Configuration.TAINT_TAG_DESC);
                ga.visitVarInsn(ALOAD, 0);
                ga.visitVarInsn(ALOAD, 2);
                Label eq = new Label();
                ga.visitJumpInsn(Opcodes.IF_ACMPEQ, eq);
                ga.visitVarInsn(ALOAD, retVar);
                ga.visitInsn(Opcodes.DUP);
                ga.visitInsn(Opcodes.ICONST_0);
                ga.visitFieldInsn(Opcodes.PUTFIELD, newReturn.getInternalName(), "val", "Z");
                ga.visitInsn(Opcodes.ARETURN);
                ga.visitLabel(eq);
                if(Configuration.IMPLICIT_TRACKING || Configuration.IMPLICIT_HEADERS_NO_TRACKING) {
                    ga.visitFrame(Opcodes.F_NEW, 4, new Object[]{className, TAINT_TAG_INTERNAL_NAME, "java/lang/Object", TAINT_TAG_INTERNAL_NAME, CONTROL_STACK_INTERNAL_NAME, newReturn.getInternalName()}, 0, new Object[]{});
                } else {
                    ga.visitFrame(Opcodes.F_NEW, 3, new Object[]{className, TAINT_TAG_INTERNAL_NAME, "java/lang/Object", TAINT_TAG_INTERNAL_NAME, newReturn.getInternalName()}, 0, new Object[]{});
                }
                ga.visitVarInsn(ALOAD, retVar);
                ga.visitInsn(Opcodes.DUP);
                ga.visitInsn(Opcodes.ICONST_1);
                ga.visitFieldInsn(Opcodes.PUTFIELD, newReturn.getInternalName(), "val", "Z");
            } else if(m.name.equals("hashCode")) {
                int retVar = (Configuration.IMPLICIT_TRACKING  || Configuration.IMPLICIT_HEADERS_NO_TRACKING ? 3 : 2);
                ga.visitVarInsn(ALOAD, retVar);
                ga.visitInsn(Opcodes.DUP);
                ga.visitInsn(Opcodes.DUP);
                NEW_EMPTY_TAINT.delegateVisit(ga);
                ga.visitFieldInsn(Opcodes.PUTFIELD, newReturn.getInternalName(), "taint", Configuration.TAINT_TAG_DESC);
                ga.visitInsn(Opcodes.ICONST_0);
                ga.visitFieldInsn(Opcodes.PUTFIELD, newReturn.getInternalName(), "val", "I");
            } else {
                switch(Type.getReturnType(newDesc.toString()).getSort()) {
                    case Type.INT:
                    case Type.SHORT:
                    case Type.BOOLEAN:
                    case Type.BYTE:
                    case Type.CHAR:
                        ga.visitInsn(Opcodes.ICONST_0);
                        break;
                    case Type.ARRAY:
                    case Type.OBJECT:
                        ga.visitInsn(Opcodes.ACONST_NULL);
                        break;
                    case Type.LONG:
                        ga.visitInsn(Opcodes.LCONST_0);
                        break;
                    case Type.DOUBLE:
                        ga.visitInsn(Opcodes.DCONST_0);
                        break;
                    case Type.FLOAT:
                        ga.visitInsn(Opcodes.FCONST_0);
                        break;
                }
            }
        } else {
            String descToCall = m.desc;
            boolean isUntaggedCall = false;
            int idx = 0;
            if((m.access & Opcodes.ACC_STATIC) == 0) {
                ga.visitVarInsn(ALOAD, 0);
                lvsToVisit.add(new LocalVariableNode("this", "L" + className + ";", null, start, end, idx));
                idx++; //this
                idx++; //this taint
            }
            for(Type t : argTypes) {
                ga.visitVarInsn(t.getOpcode(Opcodes.ILOAD), idx);


                if(TaintUtils.isWrappedType(t)) {
                    Type wrapper = TaintUtils.getWrapperType(t);
                    ga.visitMethodInsn(Opcodes.INVOKESTATIC, wrapper.getInternalName(), "unwrap", "(" + wrapper.getDescriptor() + ")" + TaintUtils.getUnwrappedType(wrapper).getDescriptor(), false);
                    // if(TaintUtils.getUnwrappedType(wrapper).getDescriptor() != t.getDescriptor()) {
                    //     ga.visitTypeInsn(Opcodes.CHECKCAST, t.getInternalName());
                    // }
                    if(TaintUtils.getUnwrappedType(wrapper).getDescriptor() != t.getDescriptor()) {
                        if ("org/rocksdb/RocksDB".equals(className) && "open".equals(methodNameToCall) && (idx == 5)) {
                            ga.visitTypeInsn(Opcodes.CHECKCAST, "[Ledu/columbia/cs/psl/phosphor/struct/LazyByteArrayObjTags;");
                        } else {
                            ga.visitTypeInsn(Opcodes.CHECKCAST, t.getInternalName());
                        }
                    }

                    lvsToVisit.add(new LocalVariableNode("phosphorNativeWrapArg" + idx, TaintUtils.getWrapperType(t).getDescriptor(), null, start, end, idx));
                } else {
                    lvsToVisit.add(new LocalVariableNode("phosphorNativeWrapArg" + idx, t.getDescriptor(), null, start, end, idx));
                }
                if (!skipUnboxing) {
                    if(t.getDescriptor().equals("[Lsun/security/pkcs11/wrapper/CK_ATTRIBUTE;")) {
                        ga.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(MultiDTaintedArray.class), "unboxCK_ATTRIBUTE", "([Lsun/security/pkcs11/wrapper/CK_ATTRIBUTE;)[Lsun/security/pkcs11/wrapper/CK_ATTRIBUTE;", false);
                    } else if(t.getDescriptor().equals("Ljava/lang/Object;") || (t.getSort() == Type.ARRAY && t.getElementType().getDescriptor().equals("Ljava/lang/Object;"))) {
                        // Need to make sure that it's not a boxed primitive array
                        ga.visitInsn(Opcodes.DUP);
                        ga.visitInsn(Opcodes.DUP);
                        Label isOK = new Label();
                        ga.visitTypeInsn(Opcodes.INSTANCEOF, "[" + Type.getDescriptor(LazyArrayObjTags.class));
                        ga.visitInsn(Opcodes.SWAP);
                        ga.visitTypeInsn(Opcodes.INSTANCEOF, Type.getInternalName(LazyArrayObjTags.class));
                        ga.visitInsn(Opcodes.IOR);
                        ga.visitJumpInsn(Opcodes.IFEQ, isOK);
                        if(isUntaggedCall) {
                            ga.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(MultiDTaintedArray.class), "unbox1D", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                        } else {
                            if(className.equals("sun/misc/Unsafe")) {
                                ga.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(MultiDTaintedArrayWithObjTag.class), "unboxRawOnly1D", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                            } else if(className.equals("sun/reflect/NativeMethodAccessorImpl") && "invoke0".equals(methodNameToCall) && Type.getType(Object.class).equals(t)) {
                                ga.loadArg(0);
                                ga.visitInsn(Opcodes.SWAP);
                                ga.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(MultiDTaintedArray.class), "unboxMethodReceiverIfNecessary", "(Ljava/lang/reflect/Method;Ljava/lang/Object;)Ljava/lang/Object;", false);
                            } else {
                                ga.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(MultiDTaintedArrayWithObjTag.class), "unboxRaw", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                            }
                        }
                        if(t.getSort() == Type.ARRAY) {
                            ga.visitTypeInsn(Opcodes.CHECKCAST, t.getInternalName());
                        }
                        FrameNode fn = TaintAdapter.getCurrentFrameNode(an);
                        ga.visitLabel(isOK);
                        TaintAdapter.acceptFn(fn, lvs);
                    } else if(!isUntaggedCall && t.getSort() == Type.ARRAY && t.getDimensions() > 1) {
                        // Need to unbox it!!
                        //ga.visitMethodInsn(Opcodes.INVOKESTATIC,
                        //Type.getInternalName(MultiDTaintedArrayWithObjTag.class),
                        //"unboxRaw", "(Ljava/lang/Object;)Ljava/lang/Object;",
                        //false);
                        if(className.equals("org/rocksdb/RocksDB") && "open".equals(methodNameToCall) && idx == 5) {
                            ga.visitInsn(Opcodes.ICONST_3);
                            ga.visitInsn(Opcodes.ICONST_1);
                            ga.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(MultiDTaintedArrayWithObjTag.class), "unboxVal", "(Ljava/lang/Object;II)Ljava/lang/Object;", false);
                        } else {
                            ga.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(MultiDTaintedArrayWithObjTag.class), "unboxRaw", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                        }
                        ga.visitTypeInsn(Opcodes.CHECKCAST, t.getInternalName());
                    }
                }
                idx += t.getSize();
                if(TaintUtils.isShadowedType(t)) {
                    lvsToVisit.add(new LocalVariableNode("phosphorNativeWrapArg" + idx, TaintUtils.getShadowTaintType(t.getDescriptor()), null, start, end, idx));
                    idx++;
                }
            }
            int opcode;
            if((m.access & Opcodes.ACC_STATIC) == 0) {
                opcode = Opcodes.INVOKESPECIAL;
            } else {
                opcode = Opcodes.INVOKESTATIC;
            }
            if(m.name.equals("<init>") && methodNameToCall.contains(TaintUtils.METHOD_SUFFIX_UNINST)) {
                //call with uninst sentinel
                descToCall = descToCall.substring(0, descToCall.indexOf(')')) + Type.getDescriptor(UninstrumentedTaintSentinel.class) + ")" + descToCall.substring(descToCall.indexOf(')') + 1);
                ga.visitInsn(Opcodes.ACONST_NULL);
                ga.visitMethodInsn(opcode, className, m.name, descToCall, false);
            } else {//favtrigger: record critical taints or trigger crash/msg drops before executing the original function
//                if(StringUtils.startsWith(this.className, "java/io/UnixFileSystem")) {
//                    if(StringUtils.startsWith(m.name, "delete0") && StringUtils.startsWith(m.desc, "(IZ)Z")) {
//
//                    }
//                } else
            	if(Instrumenter.isNativeMethodNeedsRecordSecondPara(this.className, m.name, m.desc)) {
                    ga.visitVarInsn(Opcodes.ALOAD, 0);
                    ga.visitFieldInsn(Opcodes.GETFIELD, this.className, TaintUtils.FAV_RECORD_TAG, "Z");
                    Label done1 = new Label();
                    ga.visitJumpInsn(Opcodes.IFEQ, done1);

                    ga.visitVarInsn(Opcodes.ALOAD, 0);
                    ga.visitFieldInsn(Opcodes.GETFIELD, this.className, "path", "Ljava/lang/String;");
                    int path = lvs.getTmpLV();
                    ga.visitVarInsn(Opcodes.ASTORE, path);
                    ga.visitVarInsn(Opcodes.ALOAD, 0);
                    ga.visitFieldInsn(Opcodes.GETFIELD, this.className, "path", "Ljava/lang/String;");
                    Label updatePath = new Label();
                    ga.visitJumpInsn(Opcodes.IFNONNULL, updatePath);

                    ga.visitVarInsn(Opcodes.ALOAD, 0);
                    ga.visitFieldInsn(Opcodes.GETFIELD, this.className, "fd", "Ljava/io/FileDescriptor;");
                    ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/FileDescriptor", TaintUtils.FAV_FILEDESCRIPTOR_MT, "()Ljava/lang/String;", false);
                    ga.visitVarInsn(Opcodes.ASTORE, path);

                    //check if the path is "", then it may be a write to the console,
                    //and we just skip recording this operation
                    ga.visitLabel(updatePath);
                    ga.visitVarInsn(Opcodes.ALOAD, path);
                    ga.visitLdcInsn("");
                    ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
                    ga.visitJumpInsn(Opcodes.IFNE, done1);

                    FAV_GET_RECORD_OUT.delegateVisit(ga);
                    int fileOutStream = lvs.getTmpLV();
                    ga.visitVarInsn(Opcodes.ASTORE, fileOutStream);

                    Label nullOutStream = new Label();
                    ga.visitVarInsn(Opcodes.ALOAD, fileOutStream);
                    ga.visitJumpInsn(Opcodes.IFNULL, nullOutStream);

                    ga.visitVarInsn(Opcodes.ALOAD, fileOutStream);
                	ga.visitLdcInsn(0);  //set FAV_RECORD_TAG to false, avoid dead loop
                	ga.visitFieldInsn(Opcodes.PUTFIELD, "java/io/FileOutputStream", TaintUtils.FAV_RECORD_TAG, "Z");

                    ga.visitLabel(nullOutStream);

                    FAV_GET_TIMESTAMP.delegateVisit(ga);
                    ga.visitVarInsn(Opcodes.ALOAD, fileOutStream);
                    ga.visitVarInsn(Opcodes.ALOAD, path);
                    ga.visitLdcInsn(JREType.FILE.toString());
                    JRE_FAULT_BEFORE.delegateVisit(ga);

                    ga.visitLabel(done1);

                    ga.visitMethodInsn(opcode, className, methodNameToCall, descToCall, false);

                    // ga.visitVarInsn(Opcodes.ALOAD, 0);
                    // ga.visitFieldInsn(Opcodes.GETFIELD, this.className, TaintUtils.FAV_RECORD_TAG, "Z");
                    // Label done2 = new Label();
                    // ga.visitJumpInsn(Opcodes.IFEQ, done2);

                    // FAV_GET_TIMESTAMP.delegateVisit(ga);
                    // ga.visitVarInsn(Opcodes.ALOAD, fileOutStream);
                    // ga.visitVarInsn(Opcodes.ALOAD, path);
                    // ga.visitLdcInsn(JREType.FILE.toString());
                    // JRE_FAULT_AFTER.delegateVisit(ga);

                    // ga.visitLabel(done2);
                    lvs.freeTmpLV(path);
                    lvs.freeTmpLV(fileOutStream);
                } else if(Instrumenter.isNativeMethodNeedsRecordThirdPara(this.className, m.name, m.desc)
                || Instrumenter.isNativeMethodNeedsCombineNewTaintToThirdPara(this.className, m.name, m.desc)) {
                	//SocketOutputStream socketWrite0 socketRead0
                	FAV_GET_RECORD_OUT.delegateVisit(ga);
                    int fileOutStream = lvs.getTmpLV();
                    ga.visitVarInsn(Opcodes.ASTORE, fileOutStream);

                    Label nullOutStream = new Label();
                    ga.visitVarInsn(Opcodes.ALOAD, fileOutStream);
                    ga.visitJumpInsn(Opcodes.IFNULL, nullOutStream);

                    ga.visitVarInsn(Opcodes.ALOAD, fileOutStream);
                	ga.visitLdcInsn(0);  //set FAV_RECORD_TAG to false, avoid dead loop
                	ga.visitFieldInsn(Opcodes.PUTFIELD, "java/io/FileOutputStream", TaintUtils.FAV_RECORD_TAG, "Z");

                    ga.visitLabel(nullOutStream);

                    FAV_GET_TIMESTAMP.delegateVisit(ga);
                    ga.visitVarInsn(Opcodes.ALOAD, fileOutStream);
                    ga.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                    ga.visitInsn(Opcodes.DUP);
                    ga.visitLdcInsn(FAVPathType.FAVMSG.toString()+":");
                    ga.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
                    ga.visitVarInsn(Opcodes.ALOAD, 0);
                    ga.visitFieldInsn(Opcodes.GETFIELD, this.className, "socket", "Ljava/net/Socket;");
                    ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/net/Socket", "getInetAddress", "()Ljava/net/InetAddress;", false);
                    ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/net/InetAddress", "getHostAddress", "()Ljava/lang/String;", false);
                    ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                    ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                    "()Ljava/lang/String;", false);
                    ga.visitLdcInsn(JREType.MSG.toString());
                    JRE_FAULT_BEFORE.delegateVisit(ga);

                    lvs.freeTmpLV(fileOutStream);

                    ga.visitMethodInsn(opcode, className, methodNameToCall, descToCall, false);
                } else if (Instrumenter.isNativeMethodNeedsRecordDatagramPacket(this.className, m.name, m.desc)
                || Instrumenter.isNativeMethodNeedsCombineNewTaintToDatagramPacket(this.className, m.name, m.desc)){
                	//PlainDatagramSocketImpl
                	ga.visitVarInsn(Opcodes.ALOAD, 2); //get the DatagramPacket var
                	ga.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(DatagramPacket.class));
        			int packet = lvs.getTmpLV();
        			ga.visitVarInsn(Opcodes.ASTORE, packet);

        			FAV_GET_RECORD_OUT.delegateVisit(ga);
                    int fileOutStream = lvs.getTmpLV();
                    ga.visitVarInsn(Opcodes.ASTORE, fileOutStream);

                    Label nullOutStream = new Label();
                    ga.visitVarInsn(Opcodes.ALOAD, fileOutStream);
                    ga.visitJumpInsn(Opcodes.IFNULL, nullOutStream);

                    ga.visitVarInsn(Opcodes.ALOAD, fileOutStream);
                	ga.visitLdcInsn(0);  //set FAV_RECORD_TAG to false, avoid dead loop
                	ga.visitFieldInsn(Opcodes.PUTFIELD, "java/io/FileOutputStream", TaintUtils.FAV_RECORD_TAG, "Z");

                    ga.visitLabel(nullOutStream);

                    ga.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                    ga.visitInsn(Opcodes.DUP);
                    ga.visitLdcInsn(FAVPathType.FAVMSG.toString()+":");
                    ga.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
                    ga.visitVarInsn(Opcodes.ALOAD, packet);
                    ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(DatagramPacket.class), "getAddress", "()Ljava/net/InetAddress;", false);
                    ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/net/InetAddress", "getHostAddress", "()Ljava/lang/String;", false);
                    ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                    ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                    "()Ljava/lang/String;", false);
                    int path = lvs.getTmpLV();
                    ga.visitVarInsn(Opcodes.ASTORE, path);

                    FAV_GET_TIMESTAMP.delegateVisit(ga);
                    ga.visitVarInsn(Opcodes.ALOAD, fileOutStream);
                    ga.visitVarInsn(Opcodes.ALOAD, path);
                    ga.visitLdcInsn(JREType.MSG.toString());
                    JRE_FAULT_BEFORE.delegateVisit(ga);

                    lvs.freeTmpLV(fileOutStream);
                    lvs.freeTmpLV(path);
                    lvs.freeTmpLV(packet);

                    ga.visitMethodInsn(opcode, className, methodNameToCall, descToCall, false);
                } else {
                    ga.visitMethodInsn(opcode, className, methodNameToCall, descToCall, false);
                }
                //favtrigger: end
                //favtrigger: comment
                //ga.visitMethodInsn(opcode, className, methodNameToCall, descToCall, false);
                //favtrigger: comment end
            }

            if(origReturn != newReturn) {
                if(origReturn.getSort() == Type.ARRAY && origReturn.getDimensions() > 1) {
                    Label isOK = new Label();
                    ga.visitInsn(Opcodes.DUP);
                    ga.visitJumpInsn(Opcodes.IFNULL, isOK);
                    ga.visitTypeInsn(Opcodes.CHECKCAST, "[Ljava/lang/Object;");
                    ga.visitIntInsn(Opcodes.BIPUSH, origReturn.getElementType().getSort());
                    ga.visitIntInsn(Opcodes.BIPUSH, origReturn.getDimensions());
                    ga.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(MultiDTaintedArrayWithObjTag.class), "initWithEmptyTaints", "([Ljava/lang/Object;II)Ljava/lang/Object;", false);
                    FrameNode fn = TaintAdapter.getCurrentFrameNode(an);
                    fn.stack.set(fn.stack.size() - 1, "java/lang/Object");
                    ga.visitLabel(isOK);
                    TaintAdapter.acceptFn(fn, lvs);
                    ga.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(LazyReferenceArrayObjTags.class));
                } else if(origReturn.getSort() == Type.ARRAY) {
                    if(origReturn.getElementType().getSort() == Type.OBJECT) {
                        TaintAdapter.createNewTaintArray("[Ljava/lang/Object;", an, lvs, lvs);
                    } else {
                        TaintAdapter.createNewTaintArray(origReturn.getDescriptor(), an, lvs, lvs);
                    }
                } else if(origReturn.getDescriptor().equals("Ljava/lang/Object;")) {
                    BOX_IF_NECESSARY.delegateVisit(ga);
                }
                if(origReturn.getSize() == 1) {
                    //this is the right place, since the return values are integers, the size should always be 1
                    int retIdx = lvs.getPreAllocatedReturnTypeVar(newReturn);
                    an.visitVarInsn(ALOAD, retIdx);
                    ga.visitInsn(Opcodes.SWAP);
                    if(origReturn.getSort() == Type.OBJECT || origReturn.getSort() == Type.ARRAY) {
                        ga.visitFieldInsn(Opcodes.PUTFIELD, newReturn.getInternalName(), "val", "Ljava/lang/Object;");
                    } else {
                        ga.visitFieldInsn(Opcodes.PUTFIELD, newReturn.getInternalName(), "val", origReturn.getDescriptor());
                    }
                    an.visitVarInsn(ALOAD, retIdx);
                    //favtrigger start
                    if(Instrumenter.isNativeMethodNeedsNewRtnTaint(this.className, m.name, m.desc)){
                        ga.visitLdcInsn(this.className);
                        ga.visitLdcInsn(m.name);
                        ga.visitLdcInsn(m.desc);
                        ga.visitLdcInsn(FAVTaintType.NATIVERTN.toString());
                        ga.visitLdcInsn(FAVTagType.JRE.toString());
                        if(StringUtils.startsWith(this.className, "java/lang/System")){
                            ga.visitLdcInsn("time");
                            ga.visitLdcInsn(JREType.OTHER.toString());
                        } else {
                            ga.visitVarInsn(Opcodes.ALOAD, 0);
                            ga.visitFieldInsn(Opcodes.GETFIELD, this.className, "path", "Ljava/lang/String;");
                            int path = lvs.getTmpLV(Type.getType("Ljava/lang/String;"));
                            ga.visitVarInsn(Opcodes.ASTORE, path);
                            ga.visitVarInsn(Opcodes.ALOAD, 0);
                            ga.visitFieldInsn(Opcodes.GETFIELD, this.className, "path", "Ljava/lang/String;");
                            Label finish = new Label();
                            ga.visitJumpInsn(Opcodes.IFNONNULL, finish);
                            ga.visitVarInsn(Opcodes.ALOAD, 0);
                            ga.visitFieldInsn(Opcodes.GETFIELD, this.className, "fd", "Ljava/io/FileDescriptor;");

                            ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/FileDescriptor", TaintUtils.FAV_FILEDESCRIPTOR_MT, "()Ljava/lang/String;", false);
                            ga.visitVarInsn(Opcodes.ASTORE, path);

                            ga.visitLabel(finish);
                            ga.visitVarInsn(Opcodes.ALOAD, path);
                            ga.visitLdcInsn(JREType.FILE.toString());
                            lvs.freeTmpLV(path);
                        }
                        //NEW_FAV_TAINT.delegateVisit(ga);
                        JRE_NEW_FAV_TAINT_OR_EMPTY.delegateVisit(ga);
                    }  else if(Instrumenter.isNativeMethodNeedsCombineNewTaintToSecondPara(this.className, m.name, m.desc)){
                        Label done = new Label();

                        //compute link source
                        ga.visitVarInsn(Opcodes.ALOAD, 0);
                        ga.visitFieldInsn(Opcodes.GETFIELD, this.className, "path", "Ljava/lang/String;");
                        int path = lvs.getTmpLV(Type.getType("Ljava/lang/String;"));
                        ga.visitVarInsn(Opcodes.ASTORE, path);
                        ga.visitVarInsn(Opcodes.ALOAD, 0);
                        ga.visitFieldInsn(Opcodes.GETFIELD, this.className, "path", "Ljava/lang/String;");
                        Label finish = new Label();
                        ga.visitJumpInsn(Opcodes.IFNONNULL, finish);

                        ga.visitVarInsn(Opcodes.ALOAD, 0);
                        ga.visitFieldInsn(Opcodes.GETFIELD, this.className, "fd", "Ljava/io/FileDescriptor;");
                        ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/FileDescriptor", TaintUtils.FAV_FILEDESCRIPTOR_MT, "()Ljava/lang/String;", false);
                        ga.visitVarInsn(Opcodes.ASTORE, path);

                        ga.visitLabel(finish);

                        /*
                        //check if the path is "", then it may be a read from the console,
                        //and we just skip generating new taints for this operation
                        ga.visitVarInsn(Opcodes.ALOAD, path);
                        ga.visitLdcInsn("");
                        ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
                        ga.visitJumpInsn(Opcodes.IFNE, done);
                        */

                        ga.visitVarInsn(Opcodes.ALOAD, 2); //get the LazyByteArrayObjTags var
                        ga.visitTypeInsn(Opcodes.CHECKCAST,Type.getInternalName(LazyByteArrayObjTags.class));
                        ga.visitVarInsn(Opcodes.ILOAD, 4); //get off var
                        ga.visitVarInsn(Opcodes.ILOAD, 6); //get len var
                        an.visitVarInsn(ALOAD, retIdx);
                        ga.visitFieldInsn(Opcodes.GETFIELD, newReturn.getInternalName(), "val", origReturn.getDescriptor()); //get original return
                        ga.visitLdcInsn(this.className);
                        ga.visitLdcInsn(m.name);
                        ga.visitLdcInsn(m.desc);
                        ga.visitLdcInsn(FAVTaintType.NATIVERTN.toString());
                        ga.visitLdcInsn(FAVTagType.JRE.toString());
                        ga.visitVarInsn(Opcodes.ALOAD, path);
                        ga.visitLdcInsn(JREType.FILE.toString());

                        JRE_COMBINE_NEW_FAV_TAINTS_OR_EMPTY.delegateVisit(ga);

                        //ga.visitLabel(done);
                        lvs.freeTmpLV(path);
                        Configuration.taintTagFactory.generateEmptyTaint(ga);
                    } else if (Instrumenter.isNativeMethodNeedsCombineNewTaintToThirdPara(this.className, m.name, m.desc)){
                        // ga.visitFieldInsn(Opcodes.GETSTATIC, "edu/columbia/cs/psl/phosphor/Configuration", "USE_MSGID", "Z");
                        // Label done = new Label();
                        // ga.visitJumpInsn(Opcodes.IFNE, done);

                        // ga.visitVarInsn(Opcodes.ALOAD, 4); //get the LazyByteArrayObjTags var
                        // ga.visitTypeInsn(Opcodes.CHECKCAST,Type.getInternalName(LazyByteArrayObjTags.class));
                        // ga.visitVarInsn(Opcodes.ILOAD, 6); //get off var
                        // ga.visitVarInsn(Opcodes.ILOAD, 8); //get len var
                        // an.visitVarInsn(ALOAD, retIdx);
                        // ga.visitFieldInsn(Opcodes.GETFIELD, newReturn.getInternalName(), "val", origReturn.getDescriptor()); //get original return
                        // ga.visitLdcInsn(this.className);
                        // ga.visitLdcInsn(m.name);
                        // ga.visitLdcInsn(m.desc);
                        // ga.visitLdcInsn(FAVTaintType.NATIVERTN.toString());
                        // ga.visitLdcInsn(FAVTagType.JRE.toString());

                        // ga.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                        // ga.visitInsn(Opcodes.DUP);
                        // ga.visitLdcInsn(PathType.FAVMSG.toString()+":");
                        // ga.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
                        // ga.visitVarInsn(Opcodes.ALOAD, 0);
                        // ga.visitFieldInsn(Opcodes.GETFIELD, this.className, "socket", "Ljava/net/Socket;");
                        // ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/net/Socket", "getInetAddress", "()Ljava/net/InetAddress;", false);
                        // ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/net/InetAddress", "getHostAddress", "()Ljava/lang/String;", false);
                        // ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                        // ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString","()Ljava/lang/String;", false);

                        // COMBINE_NEW_FAV_TAINTS_OR_EMPTY.delegateVisit(ga);

                        //ga.visitLabel(done);
                        Configuration.taintTagFactory.generateEmptyTaint(ga);
                    } else {
                        Configuration.taintTagFactory.generateEmptyTaint(ga);
                    }
                    //favtrigger end
                    //favtrigger: comment
                    //Configuration.taintTagFactory.generateEmptyTaint(ga);
                    //favtrigger: comment end
                    Configuration.taintTagFactory.propagateTagNative(className, m.access, m.name, m.desc, mv);
                    ga.visitFieldInsn(Opcodes.PUTFIELD, newReturn.getInternalName(), "taint", Configuration.TAINT_TAG_DESC);
                    an.visitVarInsn(ALOAD, retIdx);
                    if(origReturn.getSort() == Type.OBJECT && m.name.equals("invoke0") && className.contains("MethodAccessor")) {
                        ga.visitInsn(Opcodes.DUP);
                        ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, newReturn.getInternalName(), "unwrapPrimitives", "()V", false);
                    }
                } else {
                    int retIdx = lvs.getPreAllocatedReturnTypeVar(newReturn);
                    an.visitVarInsn(ALOAD, retIdx);
                    ga.visitInsn(Opcodes.DUP_X2);
                    ga.visitInsn(Opcodes.POP);
                    ga.visitFieldInsn(Opcodes.PUTFIELD, newReturn.getInternalName(), "val", origReturn.getDescriptor());
                    an.visitVarInsn(ALOAD, retIdx);

                    //favtrigger start: special for currentTimeMillis() and
                    //nanoTime, whose return type is long, thus the size of the
                    //return type is larger than 1
                    if(Instrumenter.isNativeMethodNeedsNewRtnTaint(this.className, m.name, m.desc)){
                        ga.visitLdcInsn(this.className);
                        ga.visitLdcInsn(m.name);
                        ga.visitLdcInsn(m.desc);
                        ga.visitLdcInsn(FAVTaintType.NATIVERTN.toString());
                        ga.visitLdcInsn(FAVTagType.JRE.toString());
                        if(StringUtils.startsWith(this.className, "java/lang/System")){
                            ga.visitLdcInsn("time");
                            ga.visitLdcInsn(JREType.OTHER.toString());
                        } else {
                            ga.visitVarInsn(Opcodes.ALOAD, 0);
                            ga.visitFieldInsn(Opcodes.GETFIELD, this.className, "path", "Ljava/lang/String;");
                            int path = lvs.getTmpLV(Type.getType("Ljava/lang/String;"));
                            ga.visitVarInsn(Opcodes.ASTORE, path);
                            ga.visitVarInsn(Opcodes.ALOAD, 0);
                            ga.visitFieldInsn(Opcodes.GETFIELD, this.className, "path", "Ljava/lang/String;");
                            Label finish = new Label();
                            ga.visitJumpInsn(Opcodes.IFNONNULL, finish);
                            ga.visitVarInsn(Opcodes.ALOAD, 0);
                            ga.visitFieldInsn(Opcodes.GETFIELD, this.className, "fd", "Ljava/io/FileDescriptor;");

                            ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/FileDescriptor", TaintUtils.FAV_FILEDESCRIPTOR_MT, "()Ljava/lang/String;", false);
                            ga.visitVarInsn(Opcodes.ASTORE, path);

                            ga.visitLabel(finish);
                            ga.visitVarInsn(Opcodes.ALOAD, path);
                            ga.visitLdcInsn(JREType.FILE.toString());
                            lvs.freeTmpLV(path);
                        }
                        //NEW_FAV_TAINT.delegateVisit(ga);
                        JRE_NEW_FAV_TAINT_OR_EMPTY.delegateVisit(ga);
                    } else {
                        Configuration.taintTagFactory.generateEmptyTaint(ga);
                    }
                    //favtrigger
                    //favtrigger: comment
                    //Configuration.taintTagFactory.generateEmptyTaint(ga);
                    //favtrigger: comment end
                    Configuration.taintTagFactory.propagateTagNative(className, m.access, m.name, m.desc, mv);
                    ga.visitFieldInsn(Opcodes.PUTFIELD, newReturn.getInternalName(), "taint", Configuration.TAINT_TAG_DESC);
                    an.visitVarInsn(ALOAD, retIdx);
                }
            } else if(origReturn.getSort() != Type.VOID && (origReturn.getDescriptor().equals("Ljava/lang/Object;") || origReturn.getDescriptor().equals("[Ljava/lang/Object;"))) {
                //Check to see if the top of the stack is a primitive array, adn if so, box it.
                if(!isUntaggedCall) {
                    ga.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(MultiDTaintedArrayWithObjTag.class),
                            "boxIfNecessary", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                    if(origReturn.getSort() == Type.ARRAY) {
                        ga.visitTypeInsn(Opcodes.CHECKCAST, "[Ljava/lang/Object;");
                    }
                }
            } else if (origReturn.getSort() == Type.VOID && Instrumenter.isNativeMethodNeedsCombineNewTaintToDatagramPacket(this.className, m.name, m.desc)){//favtrigger: PlainDatagramSocketImpl receive0
            	ga.visitVarInsn(Opcodes.ALOAD, 2); //get the DatagramPacket var
            	ga.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(DatagramPacket.class));
    			int packet = lvs.getTmpLV();
    			ga.visitVarInsn(Opcodes.ASTORE, packet);

    			ga.visitVarInsn(Opcodes.ALOAD, packet);
                ga.visitFieldInsn(Opcodes.GETFIELD, Type.getInternalName(DatagramPacket.class), "bufPHOSPHOR_WRAPPER", Type.getDescriptor(LazyByteArrayObjTags.class));
                ga.visitTypeInsn(Opcodes.CHECKCAST,Type.getInternalName(LazyByteArrayObjTags.class));
                ga.visitVarInsn(Opcodes.ALOAD, packet);
                ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(DatagramPacket.class), "getOffset", "()I", false); //get off var
                ga.visitVarInsn(Opcodes.ALOAD, packet);
                ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(DatagramPacket.class), "getLength", "()I", false); //get len var
                ga.visitInsn(Opcodes.DUP); //the actually read data length equals to the getLength() result
                ga.visitLdcInsn(this.className);
                ga.visitLdcInsn(m.name);
                ga.visitLdcInsn(m.desc);
                ga.visitLdcInsn(FAVTaintType.NATIVERTN.toString());
                ga.visitLdcInsn(FAVTagType.JRE.toString());

                /*
                ga.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                ga.visitInsn(Opcodes.DUP);
                ga.visitLdcInsn("Socket[addr=");
                ga.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
                ga.visitVarInsn(Opcodes.ALOAD, packet);
                ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(DatagramPacket.class), "getAddress", "()Ljava/net/InetAddress;", false);
                ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/net/InetAddress", "getHostAddress", "()Ljava/lang/String;", false);
                ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                ga.visitLdcInsn(",port=");
                ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                ga.visitVarInsn(Opcodes.ALOAD, packet);
                ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(DatagramPacket.class), "getPort", "()I", false);
                ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;", false);
                ga.visitLdcInsn(",localport=");
                ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                //ga.visitLdcInsn("Socket[addr=,port=,localport=");
                //ga.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
                ga.visitVarInsn(Opcodes.ALOAD, 0);
                ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/net/DatagramSocketImpl", "getLocalPort", "()I", false);
                ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;", false);
                ga.visitLdcInsn("]");
                ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder", "toString", "()Ljava/lang/String;",
                false);
                */
                ga.visitVarInsn(Opcodes.ALOAD, 2); //get the DatagramPacket var
                ga.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(DatagramPacket.class));
                ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(DatagramPacket.class), "getAddress", "()Ljava/net/InetAddress;", false);
                ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/net/InetAddress", "getHostAddress", "()Ljava/lang/String;", false);
                ga.visitLdcInsn(JREType.MSG.toString());

                //ga.visitLdcInsn("PlainDatagramSocketImpl");
                JRE_COMBINE_NEW_FAV_TAINTS_OR_EMPTY.delegateVisit(ga);
                lvs.freeTmpLV(packet);
            }
        }

        ga.visitLabel(end.getLabel());

        ga.returnValue();
        if(isPreAllocReturnType) {
            lvsToVisit.add(new LocalVariableNode("phosphorReturnHolder", newReturn.getDescriptor(), null, start, end, lvs.getPreAllocatedReturnTypeVar(newReturn)));
        }
        for(LocalVariableNode n : lvsToVisit) {
            n.accept(ga);
        }
        ga.visitMaxs(0, 0);
        ga.visitEnd();
    }

    private static void acceptAnnotationRaw(final AnnotationVisitor av, final String name, final Object value) {
        if(av != null) {
            if(value instanceof String[]) {
                String[] typeConst = (String[]) value;
                av.visitEnum(name, typeConst[0], typeConst[1]);
            } else if(value instanceof AnnotationNode) {
                AnnotationNode an = (AnnotationNode) value;
                an.accept(av.visitAnnotation(name, an.desc));
            } else if(value instanceof java.util.List) {
                AnnotationVisitor v = av.visitArray(name);
                java.util.List<?> array = (java.util.List<?>) value;
                for(Object o : array) {
                    acceptAnnotationRaw(v, null, o);
                }
                v.visitEnd();
            } else {
                av.visit(name, value);
            }
        }
    }

    private static boolean isAbstractMethodToWrap(String className, String methodName) {
        return (className.equals("org/apache/jasper/runtime/HttpJspBase") && methodName.equals("_jspService"))
                || (className.equals("javax/servlet/jsp/tagext/JspFragment") && methodName.equals("invoke"));
    }

    public static boolean isImplicitLightMethod(String owner, String name, String desc) {
        return Configuration.autoTainter.shouldInstrumentMethodForImplicitLightTracking(owner, name, desc);
    }
}
