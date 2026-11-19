use jni::objects::JObject;
use jni::sys::jstring;
use jni::JNIEnv;

#[no_mangle]
pub extern "system" fn Java___PKGJNI___MainActivity_hello(mut env: JNIEnv, _this: JObject) -> jstring {
    let out = env.new_string("Hello from Rust").expect("new_string failed");
    out.into_raw()
}
