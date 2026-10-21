"""Build a host JNI GLES adapter; production classes execute unchanged on Mesa.
Requires JDK 17, a C compiler and EGL/GLES development libraries.
"""
from pathlib import Path
import os,re,subprocess,argparse
p=argparse.ArgumentParser();p.add_argument('output',type=Path);args=p.parse_args()
root=Path(__file__).resolve().parent;repo=root.parents[2];out=args.output.resolve();out.mkdir(parents=True,exist_ok=True)
sdk=Path(os.environ.get('ANDROID_HOME','/root/android-sdk'));jhome=Path(os.environ.get('JAVA_HOME','/usr/lib/jvm/java-17-openjdk'))
constants=subprocess.check_output(['javap','-constants','-classpath',str(sdk/'platforms/android-35/android.jar'),'android.opengl.GLES20'],text=True)
needed=set(re.findall(r'GLES20\.(GL_\w+)', ''.join((repo/('app/src/main/java/com/xpe/mobile/preview/'+name+'.java')).read_text() for name in ['NoiseFieldGlRenderer','ShaderGlRenderer'])+''.join(f.read_text() for f in root.rglob('*.java'))))
values=dict(re.findall(r'public static final int (GL_\w+) = (-?\d+);',constants))
methods=[]
def method(ret,name,args,body):methods.append((ret,name,args,body))
for name,params in [('glBindTexture','int target,int texture'),('glBindFramebuffer','int target,int framebuffer'),('glEnable','int cap'),('glDisable','int cap'),('glUseProgram','int program'),('glDeleteProgram','int program'),('glDeleteShader','int shader'),('glCompileShader','int shader'),('glLinkProgram','int program'),('glEnableVertexAttribArray','int index'),('glDisableVertexAttribArray','int index'),('glActiveTexture','int texture'),('glClear','int mask'),('glFinish','')]:
 method('void',name,params,name+'('+','.join(a.split()[-1] for a in params.split(',') if a)+');')
for name,params in [('glViewport','int x,int y,int w,int h'),('glBlendFunc','int src,int dst'),('glTexParameteri','int target,int name,int value'),('glFramebufferTexture2D','int target,int attachment,int textarget,int texture,int level'),('glAttachShader','int program,int shader'),('glUniform1i','int location,int value'),('glUniform1f','int location,float value'),('glClearColor','float r,float g,float b,float a'),('glColorMask','boolean r,boolean g,boolean b,boolean a'),('glVertexAttrib4f','int index,float x,float y,float z,float w'),('glDrawArrays','int mode,int first,int count')]:
 method('void',name,params,name+'('+','.join(a.split()[-1] for a in params.split(','))+');')
for name,params in [('glCreateShader','int type'),('glCreateProgram',''),('glCheckFramebufferStatus','int target'),('glGetError','')]:method('int',name,params,'return '+name+'('+','.join(a.split()[-1] for a in params.split(',') if a)+');')
for name in ['glGenTextures','glGenFramebuffers','glDeleteTextures','glDeleteFramebuffers']:
 method('void',name,'int count,int[] names,int offset','jint* data=env->GetIntArrayElements(names,nullptr);'+name+'(count,(GLuint*)(data+offset));env->ReleaseIntArrayElements(names,data,0);')
for name in ['glGetShaderiv','glGetProgramiv']:
 method('void',name,'int object,int pname,int[] values,int offset','jint* data=env->GetIntArrayElements(values,nullptr);'+name+'(object,pname,(GLint*)(data+offset));env->ReleaseIntArrayElements(values,data,0);')
method('void','glGetIntegerv','int pname,int[] values,int offset','jint* data=env->GetIntArrayElements(values,nullptr);glGetIntegerv(pname,data+offset);env->ReleaseIntArrayElements(values,data,0);')
method('void','glBlendColor','float r,float g,float b,float a','glBlendColor(r,g,b,a);')
method('void','glUniform2f','int location,float x,float y','glUniform2f(location,x,y);')
method('void','glUniform4f','int location,float x,float y,float z,float w','glUniform4f(location,x,y,z,w);')
method('void','glUniformMatrix4fv','int location,int count,boolean transpose,float[] values,int offset','jfloat* data=env->GetFloatArrayElements(values,nullptr);glUniformMatrix4fv(location,count,transpose,data+offset);env->ReleaseFloatArrayElements(values,data,JNI_ABORT);')
method('void','glBindAttribLocation','int program,int index,String name','const char* text=env->GetStringUTFChars(name,nullptr);glBindAttribLocation(program,index,text);env->ReleaseStringUTFChars(name,text);')
for name in ['glGetUniformLocation','glGetAttribLocation']:
 method('int',name,'int object,String name','const char* text=env->GetStringUTFChars(name,nullptr);int value='+name+'(object,text);env->ReleaseStringUTFChars(name,text);return value;')
for name in ['glGetShaderInfoLog','glGetProgramInfoLog']:
 method('String',name,'int object','char log[16384];GLsizei length=0;'+name+'(object,sizeof(log),&length,log);return env->NewStringUTF(log);')
method('void','glShaderSource','int shader,String source','const char* text=env->GetStringUTFChars(source,nullptr);glShaderSource(shader,1,&text,nullptr);env->ReleaseStringUTFChars(source,text);')
for n in [2,3,4]:method('void','glUniform'+str(n)+'fv','int location,int count,float[] values,int offset','jfloat* data=env->GetFloatArrayElements(values,nullptr);glUniform'+str(n)+'fv(location,count,data+offset);env->ReleaseFloatArrayElements(values,data,JNI_ABORT);')
method('String','glGetActiveUniform','int program,int index,int[] size,int sizeOffset,int[] type,int typeOffset','char name[512];GLsizei length;GLint s;GLenum t;glGetActiveUniform(program,index,sizeof(name),&length,&s,&t,name);jint sv=s,tv=t;env->SetIntArrayRegion(size,sizeOffset,1,&sv);env->SetIntArrayRegion(type,typeOffset,1,&tv);return env->NewStringUTF(name);')
method('void','glTexImage2D','int target,int level,int internal,int width,int height,int border,int format,int type,Buffer data','glTexImage2D(target,level,internal,width,height,border,format,type,data?env->GetDirectBufferAddress(data):nullptr);')
method('void','glReadPixels','int x,int y,int w,int h,int format,int type,Buffer data','glReadPixels(x,y,w,h,format,type,env->GetDirectBufferAddress(data));')
method('void','pointer','int index,int size,int type,boolean normalized,int stride,Buffer data,int offset','glVertexAttribPointer(index,size,type,normalized,stride,(char*)env->GetDirectBufferAddress(data)+offset);')
method('void','init','','''
EGLDisplay d=eglGetPlatformDisplay(0x31DD,nullptr,nullptr);EGLint major,minor;
if(!eglInitialize(d,&major,&minor)){env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),"EGL initialization failed");return;}
eglBindAPI(EGL_OPENGL_ES_API);EGLint attrs[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES2_BIT,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_NONE};EGLConfig config;EGLint count;
eglChooseConfig(d,attrs,&config,1,&count);EGLint surfaceAttrs[]={EGL_WIDTH,1,EGL_HEIGHT,1,EGL_NONE},contextAttrs[]={EGL_CONTEXT_CLIENT_VERSION,3,EGL_NONE};
surfaceAttrs[1]=1024;surfaceAttrs[3]=1024;
EGLSurface s=eglCreatePbufferSurface(d,config,surfaceAttrs);EGLContext c=eglCreateContext(d,config,EGL_NO_CONTEXT,contextAttrs);
if(!eglMakeCurrent(d,s,s,c)){env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),"Cannot create GLES context");return;}
''')
method('int','externalTexture','int source','''
PFNEGLCREATEIMAGEKHRPROC create=(PFNEGLCREATEIMAGEKHRPROC)eglGetProcAddress("eglCreateImageKHR");
PFNEGLDESTROYIMAGEKHRPROC destroy=(PFNEGLDESTROYIMAGEKHRPROC)eglGetProcAddress("eglDestroyImageKHR");
PFNGLEGLIMAGETARGETTEXTURE2DOESPROC bind=(PFNGLEGLIMAGETARGETTEXTURE2DOESPROC)eglGetProcAddress("glEGLImageTargetTexture2DOES");
if(!create||!destroy||!bind){env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),"EGL image import unavailable");return 0;}
EGLint attrs[]={EGL_GL_TEXTURE_LEVEL_KHR,0,EGL_IMAGE_PRESERVED_KHR,EGL_TRUE,EGL_NONE};
EGLDisplay d=eglGetCurrentDisplay();EGLImageKHR image=create(d,eglGetCurrentContext(),EGL_GL_TEXTURE_2D_KHR,(EGLClientBuffer)(uintptr_t)source,attrs);
if(image==EGL_NO_IMAGE_KHR){env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),"EGL texture image creation failed");return 0;}
GLuint texture;glGenTextures(1,&texture);glBindTexture(GL_TEXTURE_EXTERNAL_OES,texture);
glTexParameteri(GL_TEXTURE_EXTERNAL_OES,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_EXTERNAL_OES,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
glTexParameteri(GL_TEXTURE_EXTERNAL_OES,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_EXTERNAL_OES,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
bind(GL_TEXTURE_EXTERNAL_OES,image);destroy(d,image);return texture;
''')
method('void','shutdown','','EGLDisplay d=eglGetCurrentDisplay();EGLContext c=eglGetCurrentContext();EGLSurface s=eglGetCurrentSurface(EGL_DRAW);eglMakeCurrent(d,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);eglDestroyContext(d,c);eglDestroySurface(d,s);eglTerminate(d);')
jtypes={'void':'void','int':'jint','boolean':'jboolean','float':'jfloat','String':'jstring','int[]':'jintArray','float[]':'jfloatArray','Buffer':'jobject'}
java=['package android.opengl;import java.nio.*;public final class GLES20 {static{System.load(System.getProperty("noise.gl.lib"));}']
for name in sorted(needed):java.append('public static final int '+name+'='+values[name]+';')
cpp=['#include <jni.h>\n#include <stdint.h>\n#include <EGL/egl.h>\n#include <EGL/eglext.h>\n#include <GLES2/gl2.h>\n#include <GLES2/gl2ext.h>']
for ret,name,params,body in methods:
 java.append('public static native '+ret+' '+name+'('+params+');')
 args=[]
 for arg in params.split(','):
  if arg:
   type,var=arg.split();args.append(jtypes[type]+' '+var)
 cpp.append('JNIEXPORT '+jtypes[ret]+' JNICALL Java_android_opengl_GLES20_'+name+'(JNIEnv* env,jclass klass'+(','+','.join(args) if args else '')+'){'+body+'}')
java.append('public static void glVertexAttribPointer(int index,int size,int type,boolean normalized,int stride,Buffer data){pointer(index,size,type,normalized,stride,data,data.position()*4);} }')
cpp=[re.sub(r'env->(\w+)\(',r'(*env)->\1(env, ',line).replace('nullptr','NULL') for line in cpp]
(out/'GLES20.java').write_text('\n'.join(java));(out/'gles.c').write_text('\n'.join(cpp))
lib=out/'libnoisegles.so'
compile_env=dict(os.environ)
if os.environ.get('NOISE_TOOLCHAIN_LIB'):compile_env['LD_LIBRARY_PATH']=os.environ['NOISE_TOOLCHAIN_LIB']
subprocess.run([os.environ.get('NOISE_CC','cc'),'-shared','-fPIC','-O2','-I'+str(jhome/'include'),'-I'+str(jhome/'include/linux'),str(out/'gles.c'),'-lEGL','-lGLESv2','-o',str(lib)],check=True,env=compile_env)
jar=next((Path.home()/'.gradle/caches/modules-2/files-2.1/org.json/json/20240303').rglob('*.jar'))
classes=repo/'app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'
cp=str(classes)+':'+str(jar);dest=out/'classes';dest.mkdir(exist_ok=True)
subprocess.run(['javac','-cp',cp,'-d',str(dest),str(out/'GLES20.java')]+[str(f) for f in root.rglob('*.java')],check=True)
(out/'classpath.txt').write_text(str(dest)+':'+cp+'\n')
print('Host GPU adapter:',lib)
