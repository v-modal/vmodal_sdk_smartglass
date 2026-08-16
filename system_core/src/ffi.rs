use std::collections::HashMap;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::ptr;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, OnceLock, RwLock};
use std::time::Duration;

use jni::EnvUnowned;
use jni::objects::{JByteArray, JByteBuffer};
use jni::sys::{jbyteArray, jint, jlong, jobject};

use crate::buffer::{PushResult, SampleFlags};
use crate::config::{CoreConfig, LiveTarget};
use crate::error::{CoreError, CoreErrorCode, CoreResult};
use crate::h264::H264Framing;
use crate::session::CoreSession;

static NEXT_HANDLE: AtomicU64 = AtomicU64::new(1);
static HANDLES: OnceLock<RwLock<HashMap<u64, Arc<CoreSession>>>> = OnceLock::new();

fn handles() -> &'static RwLock<HashMap<u64, Arc<CoreSession>>> {
    HANDLES.get_or_init(|| RwLock::new(HashMap::new()))
}

fn ffi_guard<T>(call: impl FnOnce() -> CoreResult<T>) -> CoreResult<T> {
    catch_unwind(AssertUnwindSafe(call)).unwrap_or_else(|_| {
        Err(CoreError::new(
            CoreErrorCode::InternalInvariant,
            "native core panic was contained",
            false,
        ))
    })
}

pub fn create_handle(config_bytes: &[u8]) -> CoreResult<u64> {
    ffi_guard(|| {
        let cfg = CoreConfig::from_bytes(config_bytes)?;
        let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
        if handle == 0 {
            return Err(CoreError::internal("native handle space exhausted"));
        }
        let session = CoreSession::new(cfg);
        handles()
            .write()
            .unwrap_or_else(|e| e.into_inner())
            .insert(handle, session);
        Ok(handle)
    })
}

pub fn start_handle(handle: u64, target_bytes: &[u8]) -> CoreResult<()> {
    ffi_guard(|| {
        let target = LiveTarget::from_bytes(target_bytes)?;
        get_handle(handle)?.start(target)
    })
}

#[allow(clippy::too_many_arguments)]
pub fn push_handle(
    handle: u64,
    bytes: &[u8],
    pts_us: u64,
    flags: u32,
    generation: u32,
    framing: u8,
) -> CoreResult<PushResult> {
    ffi_guard(|| {
        let flags = SampleFlags::from_bits(flags)
            .ok_or_else(|| CoreError::invalid_sample("sample flags are invalid"))?;
        let framing = match framing {
            1 => H264Framing::Avcc,
            2 => H264Framing::AnnexB,
            _ => return Err(CoreError::invalid_sample("sample framing is invalid")),
        };
        get_handle(handle)?.push_video(bytes, pts_us, flags, generation, framing)
    })
}

pub fn push_auto_handle(
    handle: u64,
    bytes: &[u8],
    pts_us: u64,
    flags: u32,
) -> CoreResult<PushResult> {
    ffi_guard(|| {
        let flags = SampleFlags::from_bits(flags)
            .ok_or_else(|| CoreError::invalid_sample("sample flags are invalid"))?;
        get_handle(handle)?.push_video_auto(bytes, pts_us, flags)
    })
}

pub fn update_target_handle(handle: u64, target_bytes: &[u8]) -> CoreResult<()> {
    ffi_guard(|| {
        let target = LiveTarget::from_bytes(target_bytes)?;
        get_handle(handle)?.update_target(target)
    })
}

pub fn poll_event_handle(handle: u64, timeout_ms: u64) -> CoreResult<Option<Vec<u8>>> {
    ffi_guard(|| {
        let timeout = Duration::from_millis(timeout_ms.min(500));
        get_handle(handle)?
            .poll_event(timeout)
            .map(|event| {
                serde_json::to_vec(&event)
                    .map_err(|_| CoreError::internal("failed to encode native event"))
            })
            .transpose()
    })
}

pub fn stats_handle(handle: u64) -> CoreResult<Vec<u8>> {
    ffi_guard(|| {
        serde_json::to_vec(&get_handle(handle)?.stats())
            .map_err(|_| CoreError::internal("failed to encode native metrics"))
    })
}

pub fn stop_handle(handle: u64, reason_code: i32) -> CoreResult<()> {
    ffi_guard(|| get_handle(handle)?.stop(reason_code))
}

pub fn destroy_handle(handle: u64) -> CoreResult<()> {
    ffi_guard(|| {
        let session = handles()
            .write()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&handle);
        if let Some(session) = session {
            session.stop(0)?;
        }
        Ok(())
    })
}

fn get_handle(handle: u64) -> CoreResult<Arc<CoreSession>> {
    if handle == 0 {
        return Err(invalid_handle());
    }
    handles()
        .read()
        .unwrap_or_else(|e| e.into_inner())
        .get(&handle)
        .cloned()
        .ok_or_else(invalid_handle)
}

fn invalid_handle() -> CoreError {
    CoreError::new(
        CoreErrorCode::InvalidHandle,
        "native handle is unknown or destroyed",
        false,
    )
}

fn status(result: CoreResult<()>) -> jint {
    result.map_or_else(|error| -(error.code as jint), |()| 0)
}

fn bytes_from_java(
    env: &jni::Env<'_>,
    array: &JByteArray<'_>,
) -> Result<Vec<u8>, jni::errors::Error> {
    let mut signed = vec![0_i8; array.len(env)?];
    array.get_region(env, 0, &mut signed)?;
    Ok(signed.into_iter().map(|byte| byte as u8).collect())
}

fn bytes_to_java<'local>(
    env: &mut jni::Env<'local>,
    bytes: &[u8],
) -> Result<JByteArray<'local>, jni::errors::Error> {
    let array = JByteArray::new(env, bytes.len())?;
    let signed: Vec<i8> = bytes.iter().map(|byte| *byte as i8).collect();
    array.set_region(env, 0, &signed)?;
    Ok(array)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_vmodal_smartglass_bridge_JniCoreBridge_nativeCreate<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _owner: jobject,
    config: JByteArray<'local>,
) -> jlong {
    unowned_env
        .with_env(|env| -> Result<jlong, jni::errors::Error> {
            let bytes = bytes_from_java(env, &config)?;
            Ok(create_handle(&bytes).unwrap_or(0) as jlong)
        })
        .resolve::<jni::errors::ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_vmodal_smartglass_bridge_JniCoreBridge_nativeStart<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _owner: jobject,
    handle: jlong,
    target: JByteArray<'local>,
) -> jint {
    unowned_env
        .with_env(|env| -> Result<jint, jni::errors::Error> {
            let bytes = bytes_from_java(env, &target)?;
            Ok(status(start_handle(handle as u64, &bytes)))
        })
        .resolve::<jni::errors::ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_vmodal_smartglass_bridge_JniCoreBridge_nativePushVideo<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _owner: jobject,
    handle: jlong,
    buffer: JByteBuffer<'local>,
    offset: jint,
    length: jint,
    pts_us: jlong,
    flags: jint,
) -> jint {
    unowned_env
        .with_env(|env| -> Result<jint, jni::errors::Error> {
            if offset < 0 || length < 0 || pts_us < 0 {
                return Ok(-(CoreErrorCode::InvalidSample as jint));
            }
            let offset = offset as usize;
            let length = length as usize;
            let capacity = env.get_direct_buffer_capacity(&buffer)?;
            let Some(end) = offset.checked_add(length).filter(|end| *end <= capacity) else {
                return Ok(-(CoreErrorCode::InvalidSample as jint));
            };
            let bytes = if length == 0 {
                &[]
            } else {
                let address = env.get_direct_buffer_address(&buffer)?;
                // SAFETY: JNI guarantees the direct buffer address is valid for its capacity,
                // the checked window is in bounds, and push_auto_handle copies before return.
                unsafe { std::slice::from_raw_parts(address.add(offset), end - offset) }
            };
            Ok(
                push_auto_handle(handle as u64, bytes, pts_us as u64, flags as u32)
                    .map_or_else(|error| -(error.code as jint), |result| result as jint),
            )
        })
        .resolve::<jni::errors::ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_vmodal_smartglass_bridge_JniCoreBridge_nativeUpdateTarget<
    'local,
>(
    mut unowned_env: EnvUnowned<'local>,
    _owner: jobject,
    handle: jlong,
    target: JByteArray<'local>,
) -> jint {
    unowned_env
        .with_env(|env| -> Result<jint, jni::errors::Error> {
            let bytes = bytes_from_java(env, &target)?;
            Ok(status(update_target_handle(handle as u64, &bytes)))
        })
        .resolve::<jni::errors::ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_vmodal_smartglass_bridge_JniCoreBridge_nativePollEvent<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _owner: jobject,
    handle: jlong,
    timeout_ms: jint,
) -> jbyteArray {
    unowned_env
        .with_env(|env| -> Result<jbyteArray, jni::errors::Error> {
            let timeout = timeout_ms.max(0) as u64;
            match poll_event_handle(handle as u64, timeout) {
                Ok(Some(bytes)) => Ok(bytes_to_java(env, &bytes)?.into_raw()),
                Ok(None) | Err(_) => Ok(ptr::null_mut()),
            }
        })
        .resolve::<jni::errors::ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_vmodal_smartglass_bridge_JniCoreBridge_nativeStats<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _owner: jobject,
    handle: jlong,
) -> jbyteArray {
    unowned_env
        .with_env(|env| -> Result<jbyteArray, jni::errors::Error> {
            match stats_handle(handle as u64) {
                Ok(bytes) => Ok(bytes_to_java(env, &bytes)?.into_raw()),
                Err(_) => Ok(ptr::null_mut()),
            }
        })
        .resolve::<jni::errors::ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_vmodal_smartglass_bridge_JniCoreBridge_nativeStop<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _owner: jobject,
    handle: jlong,
    reason_code: jint,
) -> jint {
    unowned_env
        .with_env(|_| -> Result<jint, jni::errors::Error> {
            Ok(status(stop_handle(handle as u64, reason_code)))
        })
        .resolve::<jni::errors::ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_vmodal_smartglass_bridge_JniCoreBridge_nativeDestroy<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _owner: jobject,
    handle: jlong,
) {
    unowned_env
        .with_env(|_| -> Result<(), jni::errors::Error> {
            let _ = destroy_handle(handle as u64);
            Ok(())
        })
        .resolve::<jni::errors::ThrowRuntimeExAndDefault>()
}
