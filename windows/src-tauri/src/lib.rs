use serde::Serialize;
use std::{
    collections::HashMap,
    fs::File,
    io::{BufWriter, Write},
    sync::{
        atomic::{AtomicU64, Ordering},
        Mutex,
    },
};

#[derive(Default)]
struct NativeSaveState {
    next_id: AtomicU64,
    files: Mutex<HashMap<u64, BufWriter<File>>>,
}

#[derive(Serialize)]
struct NativeSaveHandle {
    id: u64,
    path: String,
}

#[tauri::command]
fn native_save_pick(
    suggested_name: String,
    state: tauri::State<'_, NativeSaveState>,
) -> Result<Option<NativeSaveHandle>, String> {
    let Some(path) = rfd::FileDialog::new()
        .set_file_name(suggested_name)
        .save_file()
    else {
        return Ok(None);
    };
    let file = File::create(&path).map_err(|error| error.to_string())?;
    let id = state.next_id.fetch_add(1, Ordering::Relaxed) + 1;
    state
        .files
        .lock()
        .map_err(|_| "native save state lock poisoned".to_string())?
        .insert(id, BufWriter::new(file));
    Ok(Some(NativeSaveHandle {
        id,
        path: path.display().to_string(),
    }))
}

#[tauri::command]
fn native_save_write(
    id: u64,
    bytes: Vec<u8>,
    state: tauri::State<'_, NativeSaveState>,
) -> Result<(), String> {
    let mut files = state
        .files
        .lock()
        .map_err(|_| "native save state lock poisoned".to_string())?;
    let writer = files
        .get_mut(&id)
        .ok_or_else(|| "native save handle not found".to_string())?;
    writer.write_all(&bytes).map_err(|error| error.to_string())
}

#[tauri::command]
fn native_save_close(id: u64, state: tauri::State<'_, NativeSaveState>) -> Result<(), String> {
    let mut writer = state
        .files
        .lock()
        .map_err(|_| "native save state lock poisoned".to_string())?
        .remove(&id)
        .ok_or_else(|| "native save handle not found".to_string())?;
    writer.flush().map_err(|error| error.to_string())
}

#[tauri::command]
fn native_save_abort(id: u64, state: tauri::State<'_, NativeSaveState>) -> Result<(), String> {
    state
        .files
        .lock()
        .map_err(|_| "native save state lock poisoned".to_string())?
        .remove(&id);
    Ok(())
}

pub fn run() {
    tauri::Builder::default()
        .manage(NativeSaveState::default())
        .plugin(tauri_plugin_http::init())
        .plugin(tauri_plugin_websocket::init())
        .invoke_handler(tauri::generate_handler![
            native_save_pick,
            native_save_write,
            native_save_close,
            native_save_abort
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
