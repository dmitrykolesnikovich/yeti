// ex: se sts=4 sw=4 expandtab:

/*
 * Yeti language compiler java bytecode generator.
 *
 * Copyright (c) 2007,2008,2009,2010 Madis Janson
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package yeti.lang.compiler;

import yeti.renamed.asm3.Label;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.IdentityHashMap;

interface Closure {
    // Closures "wrap" references to the outside world.
    BindRef refProxy(BindRef code);
    void addVar(BindExpr binder);
}

interface CaptureWrapper {
    void genPreGet(Ctx ctx);
    void genGet(Ctx ctx);
    void genSet(Ctx ctx, Code value);
    Object captureIdentity();
    String captureType();
}

class Apply extends Code {
    final Code fun, arg;
    final int line;
    private int arity;
    BindExpr.Ref ref;

    Apply(YetiType.Type res, Code fun, Code arg, int line) {
        type = res;
        this.fun = fun;
        this.arg = arg;
        this.line = line;
    }

    void gen(Ctx ctx) {
        // TODO here we should check whether fun is BindRef or Apply to
        // function optimised into method. in this case such method call
        // should be generated. This means that the inner function should be
        // marked! (Apply) fun has to be dissected then to extract all args.
        if (ref != null) {
            Function f = (Function) ((BindExpr) ref.binder).st;
        }

        if (fun instanceof Function) {
            Function f = (Function) fun;
            LoadVar arg_ = new LoadVar();
            // inline direct calls
            // TODO: constants don't need a temp variable
            if (f.uncapture(arg_)) {
                arg.gen(ctx);
                arg_.var = ctx.localVarCount++;
                ctx.visitVarInsn(ASTORE, arg_.var);
                f.body.gen(ctx);
                return;
            }
        }

        Apply to = (arity & 1) != 0 ? (Apply) fun : this;
        to.fun.gen(ctx);
        ctx.visitLine(to.line);
        ctx.visitTypeInsn(CHECKCAST, "yeti/lang/Fun");
        if (to == this) {
            ctx.visitApply(arg, line);
        } else {
            to.arg.gen(ctx);
            arg.gen(ctx);
            ctx.visitLine(line);
            ctx.visitMethodInsn(INVOKEVIRTUAL, "yeti/lang/Fun", "apply",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
        }
    }

    Code apply(Code arg, final YetiType.Type res, int line) {
        Apply a = new Apply(res, this, arg, line);
        a.arity = arity + 1;
        if (ref != null) {
            ref.arity = a.arity;
            a.ref = ref;
        }
        return a;
    }
}

// Since the stupid JVM discards local stack when catching exceptions,
// try catch blocks have to be converted into fucking closures
// (at least for the generic case).
final class TryCatch extends CapturingClosure {
    private List catches = new ArrayList();
    private int exVar;
    Code block;
    Code cleanup;

    final class Catch extends BindRef implements Binder {
        Code handler;

        public BindRef getRef(int line) {
            return this;
        }

        void gen(Ctx ctx) {
            ctx.visitVarInsn(ALOAD, exVar);
        }
    }

    void setBlock(Code block) {
        this.type = block.type;
        this.block = block;
    }

    Catch addCatch(YetiType.Type ex) {
        Catch c = new Catch();
        c.type = ex;
        catches.add(c);
        return c;
    }

    void captureInit(Ctx ctx, Capture c, int n) {
        c.localVar = n;
        if (c.wrapper == null) {
            c.ref.gen(ctx);
            ctx.captureCast(c.captureType());
        } else {
            c.wrapper.genPreGet(ctx);
        }
    }

    void gen(Ctx ctx) {
        int argc = mergeCaptures(ctx);
        StringBuffer sigb = new StringBuffer("(");
        for (Capture c = captures; c != null; c = c.next) {
            sigb.append(c.captureType());
        }
        sigb.append(")Ljava/lang/Object;");
        String sig = sigb.toString();
        String name = "_" + ctx.methodCounter++;
        ctx.visitMethodInsn(INVOKESTATIC, ctx.className, name, sig);
        Ctx mc = ctx.newMethod(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC,
                               name, sig);
        mc.localVarCount = argc;

        Label codeStart = new Label(), codeEnd = new Label();
        Label cleanupStart = cleanup == null ? null : new Label();
        Label cleanupEntry = cleanup == null ? null : new Label();
        genClosureInit(mc);
        int retVar = -1;
        if (cleanupStart != null) {
            retVar = mc.localVarCount++;
            mc.visitInsn(ACONST_NULL);
            mc.visitVarInsn(ASTORE, retVar); // silence the JVM verifier...
        }
        mc.visitLabel(codeStart);
        block.gen(mc);
        mc.visitLabel(codeEnd);
        exVar = mc.localVarCount++;
        if (cleanupStart != null) {
            Label goThrow = new Label();
            mc.visitLabel(cleanupEntry);
            mc.visitVarInsn(ASTORE, retVar);
            mc.visitInsn(ACONST_NULL);
            mc.visitLabel(cleanupStart);
            mc.visitVarInsn(ASTORE, exVar);
            cleanup.gen(mc);
            mc.visitInsn(POP); // cleanup's null
            mc.visitVarInsn(ALOAD, exVar);
            mc.visitJumpInsn(IFNONNULL, goThrow);
            mc.visitVarInsn(ALOAD, retVar);
            mc.visitInsn(ARETURN);
            mc.visitLabel(goThrow);
            mc.visitVarInsn(ALOAD, exVar);
            mc.visitInsn(ATHROW);
        } else {
            mc.visitInsn(ARETURN);
        }
        for (int i = 0, cnt = catches.size(); i < cnt; ++i) {
            Catch c = (Catch) catches.get(i);
            Label catchStart = new Label();
            mc.visitTryCatchBlock(codeStart, codeEnd, catchStart,
                                  c.type.javaType.className());
            Label catchEnd = null;
            if (cleanupStart != null) {
                catchEnd = new Label();
                mc.visitTryCatchBlock(catchStart, catchEnd, cleanupStart, null);
            }
            mc.visitLabel(catchStart);
            mc.visitVarInsn(ASTORE, exVar);
            c.handler.gen(mc);
            if (catchEnd != null) {
                mc.visitLabel(catchEnd);
                mc.visitJumpInsn(GOTO, cleanupEntry);
            } else {
                mc.visitInsn(ARETURN);
            }
        }
        if (cleanupStart != null)
            mc.visitTryCatchBlock(codeStart, codeEnd, cleanupStart, null);
        mc.closeMethod();
    }
}

// Bind reference that is actually some wrapper created by closure (capturer).
// This class is mostly useful as a place where tail call optimization happens.
abstract class CaptureRef extends BindRef {
    Function capturer;
    BindRef ref;
    Binder[] args;
    Capture[] argCaptures;

    final class SelfApply extends Apply {
        boolean tail;
        int depth;

        SelfApply(YetiType.Type type, Code f, Code arg,
                  int line, int depth) {
            super(type, f, arg, line);
            this.depth = depth;
        }

        // evaluate call arguments and pushes values into stack
        void genArg(Ctx ctx, int i) {
            if (i > 0) {
                ((SelfApply) fun).genArg(ctx, i - 1);
            }
            arg.gen(ctx);
        }

        void gen(Ctx ctx) {
            if (!tail || depth != 0 ||
                capturer.argCaptures != argCaptures ||
                capturer.restart == null) {
                // regular apply, if tail call optimisation can't be done
                super.gen(ctx);
                return;
            }
            // push all argument values into stack - they must be evaluated
            // BEFORE modifying any of the arguments for tail-"call"-jump.
            genArg(ctx, argCaptures == null ? 0 : argCaptures.length);
            ctx.visitVarInsn(ASTORE, capturer.argVar);
            // Now assign the call argument values into argument registers.
            if (argCaptures != null) {
                for (int i = argCaptures.length; --i >= 0;) {
                    ctx.visitVarInsn(ASTORE, argCaptures[i].localVar);
                }
            }
            // And just jump into the start of the function...
            ctx.visitJumpInsn(GOTO, capturer.restart);
        }

        void markTail() {
            tail = true;
        }

        Code apply(Code arg, YetiType.Type res, int line) {
            if (depth < 0) {
                return new Apply(res, this, arg, line);
            }
            if (depth == 1) {
                // All arguments have been applied, now we have to search
                // their captures in the inner function (by looking for
                // captures matching the function arguments).
                // Resulting list will be also given to the inner function,
                // so it could copy those captures into local registers
                // to allow tail call.
                // NB. To understand this, remember that this is self-apply,
                // so current scope is also the scope of applied function.
                if (capturer.argCaptures == null) {
                    argCaptures = new Capture[args.length];
                    for (Capture c = capturer.captures; c != null;
                         c = c.next) {
                        for (int i = args.length; --i >= 0;) {
                            if (c.binder == args[i]) {
                                argCaptures[i] = c;
                                break;
                            }
                        }
                    }
                    capturer.argCaptures = argCaptures;
                }
            }
            return new SelfApply(res, this, arg, line, depth - 1);
        }
    }

    Code apply(Code arg, YetiType.Type res, int line) {
        if (args != null) {
            return new SelfApply(res, this, arg, line, args.length);
        }

        // We have application with arg x like ((f x) y) z
        // Now we take the inner function of our scope and travel
        // through its outer functions until there is one.
        //
        // If function that recognizes f as itself is met,
        // we know that this is self-application and how many
        // arguments are needed to do tail-call optimisation.
        // SelfApply with arguments count is given in that case.
        //
        // SelfApply.apply reduces the argument count until final
        // call is reached, in which case tail-call can be done,
        // if the application happens to be in tail position.
        int n = 0;
        for (Function f = capturer; f != null; ++n, f = f.outer) {
            if (f.selfBind == ref.binder) {
                args = new Binder[n];
                f = capturer.outer;
                for (int i = n; --i >= 0; f = f.outer) {
                    args[i] = f;
                }
                return new SelfApply(res, this, arg, line, n);
            }
        }
        return new Apply(res, this, arg, line);
    }
}

final class Capture extends CaptureRef implements CaptureWrapper {
    String id;
    Capture next;
    CaptureWrapper wrapper;
    Object identity;
    int localVar = -1; // -1 - use this (TryCatch captures use 0 localVar)
    boolean uncaptured;
    boolean ignoreGet;
    private String refType;

    void gen(Ctx ctx) {
        if (uncaptured) {
            ref.gen(ctx);
            return;
        }
        genPreGet(ctx);
        genGet(ctx);
    }

    String getId(Ctx ctx) {
        if (id == null) {
            id = "_".concat(Integer.toString(ctx.fieldCounter++));
        }
        return id;
    }

    boolean flagop(int fl) {
        return (fl & (PURE | ASSIGN)) != 0 && ref.flagop(fl);
    }

    Code assign(final Code value) {
        if (!ref.flagop(ASSIGN)) {
            return null;
        }
        return new Code() {
            void gen(Ctx ctx) {
                if (uncaptured) {
                    ref.assign(value).gen(ctx);
                } else {
                    genPreGet(ctx);
                    wrapper.genSet(ctx, value);
                    ctx.visitInsn(ACONST_NULL);
                }
            }
        };
    }

    public void genPreGet(Ctx ctx) {
        if (uncaptured) {
            wrapper.genPreGet(ctx);
        } else if (localVar < 0) {
            ctx.visitVarInsn(ALOAD, 0);
            if (localVar < -1) {
                ctx.intConst(-1 - localVar);
                ctx.visitInsn(AALOAD);
            } else {
                ctx.visitFieldInsn(GETFIELD, ctx.className, id,
                                   captureType());
            }
        } else {
            ctx.visitVarInsn(ALOAD, localVar);
        }
    }

    public void genGet(Ctx ctx) {
        if (wrapper != null && !ignoreGet) {
            // The object got from capture might not be the final value.
            // for example captured mutable variables are wrapped into array
            // by the binding, so the wrapper must get correct array index
            // out of the array in that case.
            wrapper.genGet(ctx);
        }
    }

    public void genSet(Ctx ctx, Code value) {
        wrapper.genSet(ctx, value);
    }

    public CaptureWrapper capture() {
        if (uncaptured) {
            return ref.capture();
        }
        return wrapper == null ? null : this;
    }

    public Object captureIdentity() {
        return wrapper == null ? this : wrapper.captureIdentity();
    }

    public String captureType() {
        if (refType == null) {
            refType = wrapper == null ? 'L' + javaType(ref.type) + ';'
                : wrapper.captureType();
            if (refType == null)
                throw new IllegalStateException("captureType:" + wrapper);
        }
        return refType;
    }

    BindRef unshare() {
        return new BindWrapper(this);
    }
}

abstract class AClosure extends Code implements Closure {
    List closureVars = new ArrayList();

    public void addVar(BindExpr binder) {
        closureVars.add(binder);
    }

    public void genClosureInit(Ctx ctx) {
        int id = -1, mvarcount = 0;
        for (int i = closureVars.size(); --i >= 0;) {
            BindExpr bind = (BindExpr) closureVars.get(i);
            if (bind.assigned && bind.captured) {
                if (id == -1) {
                    id = ctx.localVarCount++;
                }
                bind.setMVarId(this, id, mvarcount++);
            }
        }
        if (mvarcount > 0) {
            ctx.intConst(mvarcount);
            ctx.visitTypeInsn(ANEWARRAY, "java/lang/Object");
            ctx.visitVarInsn(ASTORE, id);
        }
    }
}

abstract class CapturingClosure extends AClosure {
    Capture captures;

    Capture captureRef(BindRef code) {
        for (Capture c = captures; c != null; c = c.next) {
            if (c.binder == code.binder) {
                return c;
            }
        }
        Capture c = new Capture();
        c.binder = code.binder;
        c.type = code.type;
        c.polymorph = code.polymorph;
        c.ref = code;
        c.wrapper = code.capture();
        c.next = captures;
        captures = c;
        return c;
    }

    public BindRef refProxy(BindRef code) {
        return code.flagop(DIRECT_BIND) ? code : captureRef(code);
    }

    // Called by mergeCaptures to initialize a capture.
    // It must be ok to copy capture after that.
    abstract void captureInit(Ctx fun, Capture c, int n);

    int mergeCaptures(Ctx ctx) {
        int counter = 0;
        Capture prev = null;
    next_capture:
        for (Capture c = captures; c != null; c = c.next) {
            Object identity = c.identity = c.captureIdentity();
            if (c.uncaptured)
                continue;
            // remove shared captures
            for (Capture i = captures; i != c; i = i.next) {
                if (i.identity == identity) {
                    c.id = i.id; // copy old one's id
                    c.localVar = i.localVar;
                    prev.next = c.next;
                    continue next_capture;
                }
            }
            captureInit(ctx, c, counter++);
            prev = c;
        }
        return counter;
    }
}

final class Function extends CapturingClosure implements Binder {
    // impl - Function has merged with its inner function.
    private static final int MERGED = 1;
    // impl - Function has optimised into 
    static final int METHOD = 2;

    private static final Code NEVER = new Code() {
        void gen(Ctx ctx) {
            throw new UnsupportedOperationException();
        }
    };

    String name; // name of the generated function class
    Binder selfBind;
    Code body;
    String bindName; // function (self)binding name, if there is any

    // function body has asked self reference (and the ref is not mutable)
    private CaptureRef selfRef;
    Label restart; // used by tail-call optimizer
    Function outer;
    // outer arguments to be saved in local registers (used for tail-call)
    Capture[] argCaptures;
    // argument value for inlined function
    private Code uncaptureArg;
    // register used by argument (2 for merged inner function)
    int argVar = 1;
    private int impl; // impl mode
    // impl - Function has been merged with its inner function.
    private boolean merged; 
    // How many times the argument has been used.
    // This counter is also used by argument nulling to determine
    // when it safe to assume that argument value is no more needed.
    private int argUsed;
    // Function is constant that can be statically shared.
    // Stores function instance in static final _ field and allows
    // direct-ref no-capture optimisations for function binding.
    private boolean shared;
    // Module has asked function to be a public (inner) class.
    // Useful for making Java code happy, if it wants to call the function.
    boolean publish;
    // Function uses local bindings from its module. Published function
    // should ensure module initialisation in this case, when called.
    private boolean moduleInit;

    final BindRef arg = new BindRef() {
        void gen(Ctx ctx) {
            if (uncaptureArg != null) {
                uncaptureArg.gen(ctx);
            } else {
                ctx.visitVarInsn(ALOAD, argVar);
                // inexact nulling...
                if (--argUsed == 0 && ctx.tainted == 0) {
                    ctx.visitInsn(ACONST_NULL);
                    ctx.visitVarInsn(ASTORE, argVar);
                }
            }
        }

        boolean flagop(int fl) {
            return (fl & PURE) != 0;
        }
    };

    Function(YetiType.Type type) {
        this.type = type;
        arg.binder = this;
    }

    public BindRef getRef(int line) {
        ++argUsed;
        return arg;
    }

    // uncaptures captured variables if possible
    // useful for function inlineing, don't work with self-refs
    boolean uncapture(Code arg) {
        if (selfRef != null || closureVars.size() != 0)
            return false;
        for (Capture c = captures; c != null; c = c.next) {
            c.uncaptured = true;
        }
        uncaptureArg = arg;
        return true;
    }

    void setBody(Code body) {
        this.body = body;
        if (body instanceof Function) {
            Function bodyFun = (Function) body;
            bodyFun.outer = this;
            if (argVar == 1 && !bodyFun.merged && bodyFun.selfRef == null) {
                merged = true;
                ++bodyFun.argVar;
            }
        }
    }

    // When function body refers to bindings outside of it,
    // at each closure border on the way out (to the binding),
    // a refProxy (of the ending closure) is called, possibly
    // transforming the BindRef.
    public BindRef refProxy(BindRef code) {
        if (code.flagop(DIRECT_BIND)) {
            if (code.flagop(MODULE_REQUIRED)) {
                moduleInit = true;
            }
            return code;
        }
        if (selfBind == code.binder && !code.flagop(ASSIGN)) {
            if (selfRef == null) {
                selfRef = new CaptureRef() {
                    void gen(Ctx ctx) {
                        ctx.visitVarInsn(ALOAD, 0);
                    }
                };
                selfRef.binder = selfBind;
                selfRef.type = code.type;
                selfRef.ref = code;
                selfRef.capturer = this;
            }
            return selfRef;
        }
        if (merged) {
            return code;
        }
        Capture c = captureRef(code);
        c.capturer = this;
        //expecting max 2 merged
        if (outer != null && outer.merged &&
            (code == outer.selfRef || code == outer.arg)) {
            /*
             * It's actually simple - because nested functions are merged,
             * the parent argument is now real argument that can be
             * directly accessed. Therefore capture proxy would only
             * fuck things up - and so that proxy is marked uncaptured.
             * Same goes for the parent-self-ref - it is now our this.
             *
             * Only problem is that tail-rec optimisation generates code,
             * that wants to store into the "captured" variables copy
             * before jumping back into the start of the function.
             * The optimiser sets argCaptures which should be copied
             * into local vars by function class generator, but this
             * coping is skipped as pointless for uncaptured ones.
             *
             * Therefore the captures localVar is simply set here to 1,
             * which happens to be parent args register (and is ignored
             * by selfRefs). Probable alternative would be to set it
             * when the copy code generation is skipped.
             */
            c.localVar = 1; // really evil hack for tail-recursion.
            c.uncaptured = true;
        }
        return c;
    }

    // called by mergeCaptures
    void captureInit(Ctx fun, Capture c, int n) {
        // c.getId() initialises the captures id as a side effect
        fun.cw.visitField(0, c.getId(fun), c.captureType(),
                          null, null).visitEnd();
    }

    // For functions, this generates the function class
    // An instance is also given, but capture fields are not initialised
    // (the captures are set later in the finishGen).
    void prepareGen(Ctx ctx) {
        if (merged) {
            // 2 nested lambdas have been optimised into 1
            Function inner = (Function) body;
            inner.bindName = bindName;
            inner.prepareGen(ctx);
            name = inner.name;
            return;
        }

        if (bindName == null)
            bindName = "";
        name = ctx.compilation.createClassName(ctx.className, mangle(bindName));

        publish &= shared;
        String funClass =
            argVar == 2 ? "yeti/lang/Fun2" : "yeti/lang/Fun";
        Ctx fun = ctx.newClass(publish ? ACC_PUBLIC + ACC_SUPER + ACC_FINAL
                                       : ACC_SUPER + ACC_FINAL,
                               name, funClass, null);

        if (publish)
            fun.markInnerClass(ctx, ACC_PUBLIC + ACC_STATIC + ACC_FINAL);
        mergeCaptures(fun);
        fun.createInit(shared ? ACC_PRIVATE : 0, funClass);

        Ctx apply = argVar == 2
            ? fun.newMethod(ACC_PUBLIC + ACC_FINAL, "apply",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
            : fun.newMethod(ACC_PUBLIC + ACC_FINAL, "apply",
                "(Ljava/lang/Object;)Ljava/lang/Object;");
        apply.localVarCount = argVar + 1; // this, arg
        
        if (argCaptures != null) {
            // Tail recursion needs all args to be in local registers
            // - otherwise it couldn't modify them safely before restarting
            for (int i = 0; i < argCaptures.length; ++i) {
                Capture c = argCaptures[i];
                if (!c.uncaptured) {
                    c.gen(apply);
                    c.localVar = apply.localVarCount;
                    c.ignoreGet = true;
                    apply.visitVarInsn(ASTORE, apply.localVarCount++);
                }
            }
        }
        if (moduleInit && publish) {
            apply.visitMethodInsn(INVOKESTATIC, ctx.className,
                                  "eval", "()Ljava/lang/Object;");
            apply.visitInsn(POP);
        }
        genClosureInit(apply);
        apply.visitLabel(restart = new Label());
        body.gen(apply);
        restart = null;
        apply.visitInsn(ARETURN);
        apply.closeMethod();

        Ctx valueCtx =
            shared ? fun.newMethod(ACC_STATIC, "<clinit>", "()V") : ctx;
        valueCtx.visitTypeInsn(NEW, name);
        valueCtx.visitInsn(DUP);
        valueCtx.visitInit(name, "()V");
        if (shared) {
            fun.cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL,
                              "_", "Lyeti/lang/Fun;", null, null).visitEnd();
            valueCtx.visitFieldInsn(PUTSTATIC, name, "_", "Lyeti/lang/Fun;");
            valueCtx.visitInsn(RETURN);
            valueCtx.closeMethod();
        }
    }

    void finishGen(Ctx ctx) {
        if (merged) {
            ((Function) body).finishGen(ctx);
            return;
        }
        // Capture a closure
        for (Capture c = captures; c != null; c = c.next) {
            if (c.uncaptured)
                continue;
            ctx.visitInsn(DUP);
            if (c.wrapper == null) {
                c.ref.gen(ctx);
                ctx.captureCast(c.captureType());
            } else {
                c.wrapper.genPreGet(ctx);
            }
            ctx.visitFieldInsn(PUTFIELD, name, c.id, c.captureType());
        }
        ctx.forceType("yeti/lang/Fun");
    }

    boolean flagop(int fl) {
        return merged ? ((Function) body).flagop(fl) :
                (fl & (PURE | CONST)) != 0 && (shared || captures == null);
    }

    // Check whether all captures are actually static constants.
    // If so, the function value should also be optimised into shared constant.
    boolean prepareConst(Ctx ctx) {
        if (shared) // already optimised into static constant value
            return true;

        // First try determine if we can reduce into method.
        if (false && selfBind instanceof BindExpr) {
            int arityLimit = 99999999;
            for (BindExpr.Ref i = ((BindExpr) selfBind).refs;
                 i != null; i = i.next)
                if (arityLimit > i.arity)
                    arityLimit = i.arity;
            int arity = 1;
            Function i = this;
            while (arity < arityLimit && i.body instanceof Function) {
                i = (Function) i.body;
                ++arity;
            }
            if (arity > 0 && arityLimit > 0) {
                // Merged ones are a bit tricky - they're capture set is
                // merged into their inner one, where is also their own
                // argument. Also their inner ones arg is messed up.
                // Although optimising them would be good for speed,
                // maybe it would be simpler to avoid those.
                //
                // TODO mark function arity-merge
                // XXX
                // The make-a-method trick is actually damn easy I think.
                // We have to convince the captures of the innermost
                // joined lambda to refer to the method arguments and
                // closure array instead.
                // A little problem is, that that we have to somehow
                // tell them where to get the shit...
                //
                // Now to make them know this, we have to first find them out.
                // (or alternatively they have to find out us?)
                // Probably easiest thing is to map our arguments and
                // outer capture set into good vars.
                // After that the inner captures can be scanned and made
                // to point to those values;
                //
                // XXX we should really do this after uncapture of direct refs!
                // Infact, probably in prepareGen after mergeCaptures.

                // Create capture mapping
                int argCounter = 0, captureCounter = -1;

                // map captures using binder as identify
                Map captureMapping = new IdentityHashMap();
                for (Function f = this; i != f; i = (Function) i.body)
                    captureMapping.put(f, new Integer(++argCounter));
                i.argVar = ++argCounter;
                for (Capture c = captures; c != null; c = c.next)
                    captureMapping.put(c.binder, new Integer(0));

                // Hijack the inner functions capture mapping...
                for (Capture c = i.captures; c != null; c = c.next) {
                    Object mapped = captureMapping.get(c.binder);
                    if (mapped != null) {
                        int v = ((Integer) mapped).intValue();
                        c.localVar = v == 0 ? --captureCounter : v;
                    }
                }
            }
        }

        if (merged) {
            // merged functions are hollow, their juice is in the inner function
            Function inner = (Function) body;
            inner.bindName = bindName;
            inner.publish = publish;
            if (inner.prepareConst(ctx)) {
                name = inner.name; // used by gen
                return true;
            }
            return false;
        }

        // this can be optimised into "const x", so don't touch.
        if (argUsed == 0 && argVar == 1 && body.flagop(PURE))
            return false; //captures == null;

        // Uncapture the direct bindings.
        Capture prev = null;
        boolean isConst = true;
        for (Capture c = captures; c != null; c = c.next)
            if (c.ref.flagop(DIRECT_BIND)) {
                c.uncaptured = true;
                if (prev == null)
                    captures = c.next;
                else
                    prev.next = c.next;
            } else {
                if (!c.uncaptured)
                    isConst = false;
                prev = c;
            }
        
        // If all captures were uncaptured, then the function can
        // (and will) be optimised into shared static constant.
        if (isConst) {
            shared = true;
            prepareGen(ctx);
        }
        return isConst;
    }

    void gen(Ctx ctx) {
        if (shared) {
            ctx.visitFieldInsn(GETSTATIC, name, "_", "Lyeti/lang/Fun;");
        } else if (!merged && argUsed == 0 && body.flagop(PURE) &&
                   uncapture(NEVER)) {
            // This lambda can be optimised into "const x", so do it.
            ctx.visitTypeInsn(NEW, "yeti/lang/Const");
            ctx.visitInsn(DUP);
            body.gen(ctx);
            ctx.visitInit("yeti/lang/Const", "(Ljava/lang/Object;)V");
            ctx.forceType("yeti/lang/Fun");
        } else if (prepareConst(ctx)) {
            ctx.visitFieldInsn(GETSTATIC, name, "_", "Lyeti/lang/Fun;");
        } else {
            prepareGen(ctx);
            finishGen(ctx);
        }
    }
}

final class RootClosure extends AClosure {
    Code code;
    LoadModule[] preload;
    String moduleName;
    boolean isModule;
    ModuleType moduleType;

    public BindRef refProxy(BindRef code) {
        return code;
    }

    void gen(Ctx ctx) {
        genClosureInit(ctx);
        for (int i = 0; i < preload.length; ++i) {
            if (preload[i] != null) {
                preload[i].gen(ctx);
                ctx.visitInsn(POP);
            }
        }
        code.gen(ctx);
    }
}