//! carlink core: WebTransport server for the car page, called from the
//! Android engine over JNI (class app.carlink.engine.NativeCore).

pub mod cert;
pub mod rate;
pub mod server;

use jni::objects::{GlobalRef, JByteBuffer, JClass, JObject, JString, JValue};
use jni::sys::{JNI_FALSE, JNI_TRUE, jboolean, jint, jlong};
use jni::{JNIEnv, JavaVM};
use std::sync::{Arc, Once};

struct JniListener {
    vm: JavaVM,
    obj: GlobalRef,
}

impl JniListener {
    /// Call a void Java method from a native thread. Runs inside a local
    /// frame: these threads stay attached forever, so local references
    /// would otherwise pile up.
    fn call(&self, name: &str, sig: &str, args: impl for<'a> FnOnce(&mut JNIEnv<'a>) -> jni::errors::Result<Vec<JObjectOrInt<'a>>>) {
        let Ok(mut env) = self.vm.attach_current_thread_as_daemon() else { return };
        let r = env.with_local_frame(8, |env| -> jni::errors::Result<()> {
            let args = args(env)?;
            let vals: Vec<JValue> = args.iter().map(JObjectOrInt::value).collect();
            env.call_method(&self.obj, name, sig, &vals)?;
            Ok(())
        });
        if r.is_err() && env.exception_check().unwrap_or(false) {
            let _ = env.exception_describe();
            let _ = env.exception_clear();
        }
    }
}

enum JObjectOrInt<'a> {
    Obj(JObject<'a>),
    Int(i32),
    Bool(bool),
}

impl JObjectOrInt<'_> {
    fn value(&self) -> JValue<'_, '_> {
        match self {
            JObjectOrInt::Obj(o) => JValue::Object(o),
            JObjectOrInt::Int(i) => JValue::Int(*i),
            JObjectOrInt::Bool(b) => JValue::Bool(*b as u8),
        }
    }
}

impl server::Listener for JniListener {
    fn on_session(&self, connected: bool, remote: &str) {
        self.call("onSession", "(ZLjava/lang/String;)V", |env| {
            Ok(vec![JObjectOrInt::Bool(connected), JObjectOrInt::Obj(env.new_string(remote)?.into())])
        });
    }

    fn on_control(&self, json: &str) {
        self.call("onControl", "(Ljava/lang/String;)V", |env| Ok(vec![JObjectOrInt::Obj(env.new_string(json)?.into())]));
    }

    fn on_keyframe_needed(&self, pane: u16) {
        self.call("onKeyframeNeeded", "(I)V", |_| Ok(vec![JObjectOrInt::Int(pane as i32)]));
    }

    fn on_rate(&self, level: u32) {
        self.call("onRate", "(I)V", |_| Ok(vec![JObjectOrInt::Int(level as i32)]));
    }
}

fn init_logging() {
    static ONCE: Once = Once::new();
    ONCE.call_once(|| {
        #[cfg(target_os = "android")]
        android_logger::init_once(android_logger::Config::default().with_max_level(log::LevelFilter::Info).with_tag("carlink-core"));
    });
}

/// The Kotlin side guarantees no call uses a handle after nativeStop
/// (Engine keeps it in an AtomicLong and zeroes it first).
fn handle_ref(h: jlong) -> Option<&'static server::Server> {
    if h == 0 { None } else { Some(unsafe { &*(h as *const server::Server) }) }
}

fn get_string(env: &mut JNIEnv, s: &JString) -> Option<String> {
    env.get_string(s).ok().map(Into::into)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_carlink_engine_NativeCore_nativeStart(
    mut env: JNIEnv,
    _class: JClass,
    port: jint,
    code: JString,
    allow: JString,
    listener: JObject,
) -> jlong {
    init_logging();
    let (Some(code), Some(allow)) = (get_string(&mut env, &code), get_string(&mut env, &allow)) else { return 0 };
    let (Ok(vm), Ok(obj)) = (env.get_java_vm(), env.new_global_ref(listener)) else { return 0 };
    let opts = server::Options { port: port as u16, code, allow };
    match server::Server::start(opts, Arc::new(JniListener { vm, obj })) {
        Ok(s) => Box::into_raw(Box::new(s)) as jlong,
        Err(e) => {
            log::error!("start failed: {e:#}");
            let _ = env.throw_new("java/io/IOException", format!("{e:#}"));
            0
        }
    }
}

/// Send one encoded access unit from a direct ByteBuffer (MediaCodec output).
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_carlink_engine_NativeCore_nativeSendFrame(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    pane: jint,
    buffer: JByteBuffer,
    offset: jint,
    size: jint,
    pts_us: jlong,
    flags: jint,
) -> jboolean {
    let Some(server) = handle_ref(handle) else { return JNI_FALSE };
    let (Ok(offset), Ok(size), Ok(pane)) = (usize::try_from(offset), usize::try_from(size), u16::try_from(pane)) else {
        return JNI_FALSE;
    };
    let (Ok(ptr), Ok(cap)) = (env.get_direct_buffer_address(&buffer), env.get_direct_buffer_capacity(&buffer)) else {
        return JNI_FALSE;
    };
    if ptr.is_null() || offset.checked_add(size).is_none_or(|end| end > cap) {
        return JNI_FALSE;
    }
    let data = unsafe { std::slice::from_raw_parts(ptr.add(offset), size) };
    if server.send_frame(pane, flags as u8, pts_us as u64, data) { JNI_TRUE } else { JNI_FALSE }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_carlink_engine_NativeCore_nativeSendControl(mut env: JNIEnv, _class: JClass, handle: jlong, json: JString) {
    let Some(server) = handle_ref(handle) else { return };
    if let Some(s) = get_string(&mut env, &json) {
        server.send_control(&s);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_carlink_engine_NativeCore_nativeStop(_env: JNIEnv, _class: JClass, handle: jlong) {
    if handle != 0 {
        let server = unsafe { Box::from_raw(handle as *mut server::Server) };
        server.shutdown();
    }
}

/// Hex SHA-256 of this week's certificate for a pairing code (diagnostics).
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_carlink_engine_NativeCore_nativeCertHash<'a>(mut env: JNIEnv<'a>, _class: JClass, code: JString) -> JString<'a> {
    let code = get_string(&mut env, &code).unwrap_or_default();
    env.new_string(cert::make(&code, cert::now_unix()).hash_hex()).unwrap_or_default()
}
