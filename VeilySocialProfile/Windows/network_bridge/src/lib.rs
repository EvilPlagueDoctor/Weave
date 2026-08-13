use daemon_network_sdk::{
    AppActivityLevel, AppStoreDescriptor, AppStoreWrite, ClientError, NetworkApp,
    NetworkIdentity,
};
use std::{
    collections::{HashMap, HashSet},
    ffi::{c_char, c_void, CStr},
    ptr,
    sync::{mpsc, Arc, Mutex},
    thread,
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use veilysocial_backbone::{
    decode_gossip, encode_gossip, DiscoveryIntent, GossipMessage, KnowledgeCache,
    ProfileHint, ProfilePageRecord, VerificationState, WeightedSignature,
    DEFAULT_CLUSTER_COUNT, PROTOCOL_VERSION,
};

const APP_ID: &str = "veilknit.veilysocial.profile.v1";
const APP_NAME: &str = "VeilySocial Profiles";
const PROFILE_STORE_NAME: &str = "veilysocial-profile-root-v1";
const PROFILE_SUBKEY: u32 = 0;
const PEER_REFRESH_SECS: u64 = 20;
const GOSSIP_INTERVAL_SECS: u64 = 12;
const MAX_PROFILE_TEXT_BYTES: usize = 4 * 1024 * 1024;

fn now() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs()
}

#[derive(Clone, Default)]
struct ProfileView {
    hint: Option<ProfileHint>,
    score: f32,
    positive_similarity: f32,
    negative_similarity: f32,
    name_score: f32,
    term_score: f32,
}

#[derive(Default)]
struct Snapshot {
    status: String,
    main_dht: String,
    profile_root: String,
    recent: Vec<ProfileView>,
    search: Vec<ProfileView>,
    profile_texts: HashMap<String, String>,
    debug_log: String,
    cluster_text: String,
    peers: usize,
    verified: usize,
    gossip_sent: u64,
    gossip_received: u64,
}

impl Snapshot {
    fn log(&mut self, text: impl AsRef<str>) {
        self.debug_log.push_str(text.as_ref());
        self.debug_log.push('\n');
        if self.debug_log.len() > 100_000 {
            let cut = self.debug_log.char_indices().nth(20_000).map(|(i, _)| i).unwrap_or(0);
            self.debug_log.drain(..cut);
        }
    }
}

#[derive(Debug)]
enum Command {
    Publish {
        profile_text: String,
        name: String,
        description: String,
        features_text: String,
    },
    Search {
        name_query: String,
        positive_terms: String,
        positive_main_dht: String,
        negative_main_dht: String,
        avoidance_strength: f32,
        common_term_penalty: f32,
        stuffing_penalty: f32,
        novelty_weight: f32,
    },
    Refresh,
    GossipNow,
    Stop,
}

struct Bridge {
    tx: mpsc::Sender<Command>,
    snapshot: Arc<Mutex<Snapshot>>,
}

#[repr(C)]
pub struct VsnProfileSummary {
    pub main_dht: [c_char; 384],
    pub profile_root_dht: [c_char; 384],
    pub name: [c_char; 128],
    pub description: [c_char; 512],
    pub features: [c_char; 512],
    pub verification: [c_char; 32],
    pub generation: u64,
    pub updated_at: u64,
    pub observed_at: u64,
    pub score: f32,
    pub positive_similarity: f32,
    pub negative_similarity: f32,
    pub name_score: f32,
    pub term_score: f32,
}

impl Default for VsnProfileSummary {
    fn default() -> Self {
        Self {
            main_dht: [0; 384], profile_root_dht: [0; 384], name: [0; 128],
            description: [0; 512], features: [0; 512], verification: [0; 32],
            generation: 0, updated_at: 0, observed_at: 0, score: 0.0,
            positive_similarity: 0.0, negative_similarity: 0.0, name_score: 0.0,
            term_score: 0.0,
        }
    }
}

#[repr(C)]
#[derive(Default, Copy, Clone)]
pub struct VsnCounters {
    pub peers: u32,
    pub verified: u32,
    pub gossip_sent: u64,
    pub gossip_received: u64,
}

fn write_fixed<const N: usize>(dst: &mut [c_char; N], src: &str) {
    *dst = [0; N];
    let bytes = src.as_bytes();
    let n = bytes.len().min(N.saturating_sub(1));
    for i in 0..n { dst[i] = bytes[i] as c_char; }
}

fn fill_summary(dst: &mut VsnProfileSummary, view: &ProfileView) {
    *dst = VsnProfileSummary::default();
    let Some(h) = &view.hint else { return; };
    write_fixed(&mut dst.main_dht, &h.main_dht);
    write_fixed(&mut dst.profile_root_dht, &h.profile_root_dht);
    write_fixed(&mut dst.name, &h.name);
    write_fixed(&mut dst.description, &h.description);
    write_fixed(&mut dst.features, &h.features.join(", "));
    write_fixed(&mut dst.verification, &format!("{:?}", h.verification));
    dst.generation = h.generation;
    dst.updated_at = h.updated_at;
    dst.observed_at = h.observed_at;
    dst.score = view.score;
    dst.positive_similarity = view.positive_similarity;
    dst.negative_similarity = view.negative_similarity;
    dst.name_score = view.name_score;
    dst.term_score = view.term_score;
}

unsafe fn bridge<'a>(handle: *mut c_void) -> Option<&'a Bridge> {
    (handle as *mut Bridge).as_ref()
}

unsafe fn cstr(value: *const c_char) -> String {
    if value.is_null() { return String::new(); }
    CStr::from_ptr(value).to_string_lossy().into_owned()
}

fn copy_string(value: &str, buffer: *mut c_char, capacity: usize) -> usize {
    let required = value.as_bytes().len().saturating_add(1);
    if buffer.is_null() || capacity == 0 { return required; }
    let n = value.as_bytes().len().min(capacity.saturating_sub(1));
    unsafe {
        ptr::copy_nonoverlapping(value.as_ptr(), buffer as *mut u8, n);
        *buffer.add(n) = 0;
    }
    required
}

#[no_mangle]
pub extern "C" fn vsn_start() -> *mut c_void {
    let (tx, rx) = mpsc::channel();
    let snapshot = Arc::new(Mutex::new(Snapshot { status: "Starting network bridge…".into(), ..Default::default() }));
    let worker_snapshot = snapshot.clone();
    thread::spawn(move || {
        let runtime = match tokio::runtime::Runtime::new() {
            Ok(v) => v,
            Err(e) => { worker_snapshot.lock().unwrap().status = format!("Tokio runtime failed: {e}"); return; }
        };
        if let Err(e) = runtime.block_on(network_worker(rx, worker_snapshot.clone())) {
            let mut s = worker_snapshot.lock().unwrap();
            s.status = format!("Network worker stopped: {e}");
            s.log(format!("network worker stopped: {e}"));
        }
    });
    Box::into_raw(Box::new(Bridge { tx, snapshot })) as *mut c_void
}

#[no_mangle]
pub unsafe extern "C" fn vsn_stop(handle: *mut c_void) {
    if handle.is_null() { return; }
    let boxed = Box::from_raw(handle as *mut Bridge);
    let _ = boxed.tx.send(Command::Stop);
}

#[no_mangle]
pub unsafe extern "C" fn vsn_publish(
    handle: *mut c_void, profile_text: *const c_char, name: *const c_char,
    description: *const c_char, features_text: *const c_char,
) -> i32 {
    let Some(b) = bridge(handle) else { return 0; };
    let command = Command::Publish {
        profile_text: cstr(profile_text), name: cstr(name), description: cstr(description),
        features_text: cstr(features_text),
    };
    b.tx.send(command).is_ok() as i32
}

#[no_mangle]
pub unsafe extern "C" fn vsn_search(
    handle: *mut c_void, name_query: *const c_char, positive_terms: *const c_char,
    positive_main_dht: *const c_char, negative_main_dht: *const c_char,
    avoidance_strength: f32, common_term_penalty: f32, stuffing_penalty: f32,
    novelty_weight: f32,
) -> i32 {
    let Some(b) = bridge(handle) else { return 0; };
    b.tx.send(Command::Search {
        name_query: cstr(name_query), positive_terms: cstr(positive_terms),
        positive_main_dht: cstr(positive_main_dht), negative_main_dht: cstr(negative_main_dht),
        avoidance_strength, common_term_penalty, stuffing_penalty, novelty_weight,
    }).is_ok() as i32
}

#[no_mangle]
pub unsafe extern "C" fn vsn_refresh(handle: *mut c_void) -> i32 {
    bridge(handle).map(|b| b.tx.send(Command::Refresh).is_ok() as i32).unwrap_or(0)
}

#[no_mangle]
pub unsafe extern "C" fn vsn_gossip_now(handle: *mut c_void) -> i32 {
    bridge(handle).map(|b| b.tx.send(Command::GossipNow).is_ok() as i32).unwrap_or(0)
}

#[no_mangle]
pub unsafe extern "C" fn vsn_recent_count(handle: *mut c_void) -> usize {
    bridge(handle).map(|b| b.snapshot.lock().unwrap().recent.len()).unwrap_or(0)
}

#[no_mangle]
pub unsafe extern "C" fn vsn_recent_at(handle: *mut c_void, index: usize, out: *mut VsnProfileSummary) -> i32 {
    if out.is_null() { return 0; }
    let Some(b) = bridge(handle) else { return 0; };
    let snap = b.snapshot.lock().unwrap();
    let Some(view) = snap.recent.get(index) else { return 0; };
    fill_summary(&mut *out, view); 1
}

#[no_mangle]
pub unsafe extern "C" fn vsn_search_count(handle: *mut c_void) -> usize {
    bridge(handle).map(|b| b.snapshot.lock().unwrap().search.len()).unwrap_or(0)
}

#[no_mangle]
pub unsafe extern "C" fn vsn_search_at(handle: *mut c_void, index: usize, out: *mut VsnProfileSummary) -> i32 {
    if out.is_null() { return 0; }
    let Some(b) = bridge(handle) else { return 0; };
    let snap = b.snapshot.lock().unwrap();
    let Some(view) = snap.search.get(index) else { return 0; };
    fill_summary(&mut *out, view); 1
}

#[no_mangle]
pub unsafe extern "C" fn vsn_get_profile_text(handle: *mut c_void, main_dht: *const c_char, buffer: *mut c_char, capacity: usize) -> usize {
    let Some(b) = bridge(handle) else { return 0; };
    let key = cstr(main_dht);
    let snap = b.snapshot.lock().unwrap();
    snap.profile_texts.get(&key).map(|v| copy_string(v, buffer, capacity)).unwrap_or(0)
}

macro_rules! string_getter {
    ($name:ident, $field:ident) => {
        #[no_mangle]
        pub unsafe extern "C" fn $name(handle: *mut c_void, buffer: *mut c_char, capacity: usize) -> usize {
            let Some(b) = bridge(handle) else { return 0; };
            let snap = b.snapshot.lock().unwrap();
            copy_string(&snap.$field, buffer, capacity)
        }
    }
}
string_getter!(vsn_get_status, status);
string_getter!(vsn_get_main_dht, main_dht);
string_getter!(vsn_get_profile_root, profile_root);
string_getter!(vsn_get_debug_log, debug_log);
string_getter!(vsn_get_cluster_text, cluster_text);

#[no_mangle]
pub unsafe extern "C" fn vsn_get_counters(handle: *mut c_void, out: *mut VsnCounters) -> i32 {
    if out.is_null() { return 0; }
    let Some(b) = bridge(handle) else { return 0; };
    let snap = b.snapshot.lock().unwrap();
    *out = VsnCounters { peers: snap.peers as u32, verified: snap.verified as u32, gossip_sent: snap.gossip_sent, gossip_received: snap.gossip_received };
    1
}

async fn connect_app(snapshot: &Arc<Mutex<Snapshot>>) -> Result<NetworkApp, String> {
    loop {
        match NetworkApp::builder(APP_ID).display_name(APP_NAME).connect().await {
            Ok(app) => return Ok(app),
            Err(ClientError::AuthorizationRequired(request)) => {
                snapshot.lock().unwrap().status = format!(
                    "Authorization required: approve newest '{}' request in daemon Applications (request #{})",
                    APP_NAME, request.request_id()
                );
                match request.wait().await {
                    Ok(app) => return Ok(app),
                    Err(error) => return Err(error.to_string()),
                }
            }
            Err(error) => {
                snapshot.lock().unwrap().status = format!("Daemon unavailable: {error}. Retrying…");
                tokio::time::sleep(Duration::from_secs(3)).await;
            }
        }
    }
}

async fn ensure_profile_store(app: &NetworkApp) -> Result<AppStoreDescriptor, ClientError> {
    if let Some(store) = app.stores().await?.into_iter().find(|s| s.name == PROFILE_STORE_NAME) { return Ok(store); }
    app.create_store(PROFILE_STORE_NAME, 4).await
}

async fn upload_profile_blob(app: &NetworkApp, bytes: &[u8]) -> Result<daemon_network_sdk::BlobDescriptor, ClientError> {
    let client = app.advanced_client();
    let upload = client.begin_blob_upload("application/x-veilysocial-vspf-text;version=3").await?;
    for chunk in bytes.chunks(256 * 1024) {
        if let Err(error) = client.append_blob_upload(&upload.upload_id, chunk).await {
            let _ = client.abort_blob_upload(&upload.upload_id).await;
            return Err(error);
        }
    }
    client.finish_blob_upload(&upload.upload_id, None).await
}

async fn publish_profile(
    app: &NetworkApp, store: &AppStoreDescriptor, main_dht: &str, profile_text: String,
    name: String, description: String, features_text: String, prior: Option<&ProfilePageRecord>,
) -> Result<(ProfilePageRecord, String), ClientError> {
    if profile_text.len() > MAX_PROFILE_TEXT_BYTES {
        return Err(ClientError::UnexpectedResponse(format!("profile document exceeds {} bytes", MAX_PROFILE_TEXT_BYTES)));
    }
    if !profile_text.starts_with("VEILYSOCIAL_PROFILE_") {
        return Err(ClientError::UnexpectedResponse("profile is not a VSPF text envelope".into()));
    }
    let bytes = profile_text.as_bytes();
    let blob = upload_profile_blob(app, bytes).await?;
    let features: Vec<String> = features_text.split(|c| c == ',' || c == '\n' || c == ';')
        .map(str::trim).filter(|s| !s.is_empty()).take(64).map(ToString::to_string).collect();
    let record = ProfilePageRecord {
        schema_version: 1, main_dht: main_dht.to_string(), profile_root_dht: store.record_key.clone(),
        generation: prior.map_or(1, |p| p.generation.saturating_add(1)), updated_at: now(),
        name: name.trim().chars().take(80).collect(), description: description.chars().take(1500).collect(), features,
        profile_blob_id: blob.blob_id.clone(), profile_blob_root: blob.root_record_key.clone(),
        profile_sha256_hex: blob.sha256_hex.clone(), profile_bytes: blob.total_bytes, vspf_version: 3,
    };
    let encoded = serde_json::to_vec(&record).map_err(|e| ClientError::UnexpectedResponse(e.to_string()))?;
    app.write_store(&store.store_id, None, &[AppStoreWrite { location: PROFILE_SUBKEY, value: encoded }]).await?;
    app.advanced_client().register_app_root(&store.record_key).await?;
    if let Some(old) = prior {
        if old.profile_blob_id != record.profile_blob_id && !old.profile_blob_id.is_empty() {
            let _ = app.advanced_client().delete_blob(&old.profile_blob_id).await;
        }
    }
    Ok((record, profile_text))
}

async fn fetch_profile_document(app: &NetworkApp, record: &ProfilePageRecord) -> Option<String> {
    let (blob, bytes) = app.advanced_client().download_blob(&record.profile_blob_root).await.ok()?;
    if blob.sha256_hex != record.profile_sha256_hex || blob.total_bytes != record.profile_bytes || bytes.len() > MAX_PROFILE_TEXT_BYTES { return None; }
    let text = String::from_utf8(bytes).ok()?;
    if !text.starts_with("VEILYSOCIAL_PROFILE_") { return None; }
    Some(text)
}

async fn verify_profile(
    app: &NetworkApp, expected_main_dht: &str, root: &str, cache: &mut KnowledgeCache,
    records: &mut HashMap<String, ProfilePageRecord>, docs: &mut HashMap<String, String>, snapshot: &Arc<Mutex<Snapshot>>,
) {
    let Ok(read) = app.read_public_store(root, &[PROFILE_SUBKEY], true).await else { return; };
    let Some(bytes) = read.values.first().and_then(|v| v.value().ok()).flatten() else { return; };
    let Ok(record) = serde_json::from_slice::<ProfilePageRecord>(&bytes) else { return; };
    if record.main_dht != expected_main_dht || record.profile_root_dht != root { return; }
    if let Some(text) = fetch_profile_document(app, &record).await {
        docs.insert(record.main_dht.clone(), text);
        records.insert(record.main_dht.clone(), record.clone());
        cache.upsert(record.to_hint(now(), VerificationState::DhtVerified), Some("dht"), now());
        snapshot.lock().unwrap().log(format!("DHT/blob verified profile {} ({})", record.name, expected_main_dht));
    }
}

async fn refresh_peers(
    app: &NetworkApp, known_peers: &mut Vec<String>, cache: &mut KnowledgeCache,
    records: &mut HashMap<String, ProfilePageRecord>, docs: &mut HashMap<String, String>, snapshot: &Arc<Mutex<Snapshot>>,
) {
    match app.advanced_client().list_app_peers(1000, true).await {
        Ok(page) => {
            let total_cached = page.total_cached; let search_state = page.search_state.clone();
            known_peers.clear();
            for peer in page.peers {
                if peer.main_dht == app.local_user().identity.as_str() { continue; }
                known_peers.push(peer.main_dht.clone());
                let root = if let Some(root) = peer.app_root_dht { Some(root) }
                    else { app.advanced_client().get_app_root(&peer.main_dht, true).await.ok().and_then(|r| r.root_dht) };
                if let Some(root) = root { verify_profile(app, &peer.main_dht, &root, cache, records, docs, snapshot).await; }
            }
            let _ = app.advanced_client().set_app_activity(AppActivityLevel::Interactive, known_peers, 90).await;
            snapshot.lock().unwrap().log(format!("app peers: cached={} returned={} search={}", total_cached, known_peers.len(), search_state));
        }
        Err(error) => snapshot.lock().unwrap().log(format!("list_app_peers failed: {error}")),
    }
}

fn deterministic_peer_sample(peers: &[String], seed: u64, limit: usize) -> Vec<String> {
    let mut keyed: Vec<_> = peers.iter().map(|peer| {
        let mut h = seed ^ 0x9e3779b97f4a7c15;
        for b in peer.as_bytes() { h = h.rotate_left(5) ^ (*b as u64); h = h.wrapping_mul(0x100000001b3); }
        (h, peer.clone())
    }).collect();
    keyed.sort_by_key(|x| x.0); keyed.into_iter().take(limit).map(|x| x.1).collect()
}

async fn gossip_summary(app: &NetworkApp, peers: &[String], cache: &KnowledgeCache, snapshot: &Arc<Mutex<Snapshot>>) -> u64 {
    let timestamp = now();
    let mut clusters = cache.clusters(DEFAULT_CLUSTER_COUNT, cache.generation() ^ timestamp / 60);
    for cluster in &mut clusters { cluster.exemplars.truncate(1); cluster.samples.truncate(1); }
    let message = GossipMessage::Summary { protocol_version: PROTOCOL_VERSION, generation: cache.generation(), created_at: timestamp, clusters };
    let Ok(bytes) = encode_gossip(&message) else { return 0; };
    if bytes.len() > 8 * 1024 { snapshot.lock().unwrap().log(format!("summary skipped: {} bytes", bytes.len())); return 0; }
    let mut sent = 0;
    for peer in deterministic_peer_sample(peers, timestamp, 6) {
        if let Ok(identity) = NetworkIdentity::parse(peer) { if app.gossip(&identity, &bytes).await.is_ok() { sent += 1; } }
    }
    sent
}

async fn process_gossip(
    app: &NetworkApp, gossip: GossipMessage, source: &str, cache: &mut KnowledgeCache,
    records: &mut HashMap<String, ProfilePageRecord>, docs: &mut HashMap<String, String>,
    known_peers: &[String], query_reply_at: &mut HashMap<String, u64>, snapshot: &Arc<Mutex<Snapshot>>,
) {
    if gossip.protocol_version() != PROTOCOL_VERSION { return; }
    match gossip {
        GossipMessage::Summary { clusters, .. } => for cluster in clusters {
            let representative = cluster.exemplars.first().cloned().unwrap_or_default();
            for sample in cluster.samples { cache.upsert(sample.approximate_hint(now(), representative.clone()), Some(source), now()); }
        },
        GossipMessage::ProfileAnnounce { mut profile, .. } => {
            let main = profile.main_dht.clone();
            let root = profile.profile_root_dht.clone();
            profile.verification = VerificationState::GossipHint;
            cache.upsert(profile, Some(source), now());
            if !root.is_empty() { verify_profile(app, &main, &root, cache, records, docs, snapshot).await; }
        },
        GossipMessage::SimilarityQuery { request_id, intent, limit, .. } => {
            if !known_peers.iter().any(|peer| peer == source) { return; }
            let current = now(); if query_reply_at.get(source).is_some_and(|last| last.saturating_add(30) > current) { return; }
            query_reply_at.insert(source.to_string(), current);
            let samples = cache.search(&intent, limit.min(6)).into_iter().map(|r| r.hint).collect();
            let response = GossipMessage::SimilarityResponse { protocol_version: PROTOCOL_VERSION, request_id, samples };
            if let Ok(bytes) = encode_gossip(&response) { if bytes.len() <= 8 * 1024 { if let Ok(identity) = NetworkIdentity::parse(source.to_string()) { let _ = app.gossip(&identity, bytes).await; } } }
        },
        GossipMessage::SimilarityResponse { samples, request_id, .. } => {
            let mut verify = Vec::new();
            for mut hint in samples {
                verify.push((hint.main_dht.clone(), hint.profile_root_dht.clone()));
                hint.verification = VerificationState::GossipHint;
                cache.upsert(hint, Some(source), now());
            }
            for (main, root) in verify { if !root.is_empty() { verify_profile(app, &main, &root, cache, records, docs, snapshot).await; } }
            snapshot.lock().unwrap().log(format!("search response {} from {}", request_id, source));
        }
    }
}

fn build_intent(
    cache: &KnowledgeCache, name_query: String, positive_terms: String, positive_main_dht: String,
    negative_main_dht: String, avoidance_strength: f32, common_term_penalty: f32,
    stuffing_penalty: f32, novelty_weight: f32,
) -> DiscoveryIntent {
    let positive = cache.get(&positive_main_dht).map(|p| vec![WeightedSignature { signature: p.hint.minhash.clone(), weight: 1.0 }]).unwrap_or_default();
    let negative = cache.get(&negative_main_dht).map(|p| vec![WeightedSignature { signature: p.hint.minhash.clone(), weight: 1.0 }]).unwrap_or_default();
    let terms = positive_terms.split(|c: char| c == ',' || c == ';' || c.is_whitespace()).map(str::trim).filter(|s| !s.is_empty()).take(32).map(ToString::to_string).collect();
    DiscoveryIntent { positive, negative, name_query: if name_query.trim().is_empty() { None } else { Some(name_query.trim().to_string()) }, positive_terms: terms, negative_terms: Vec::new(), avoidance_strength, common_term_penalty, stuffing_penalty, novelty_weight }
}

fn emit_snapshots(
    cache: &KnowledgeCache, docs: &HashMap<String, String>, main_dht: &str, peers: &[String],
    active_intent: &Option<DiscoveryIntent>, sent: u64, received: u64, snapshot: &Arc<Mutex<Snapshot>>,
) {
    let recent: Vec<ProfileView> = cache.recent().into_iter().filter(|c| c.hint.main_dht != main_dht).take(50).map(|c| ProfileView { hint: Some(c.hint.clone()), ..Default::default() }).collect();
    let search = active_intent.as_ref().map(|intent| cache.search(intent, 100).into_iter().map(|r| ProfileView {
        hint: Some(r.hint), score: r.score, positive_similarity: r.positive_similarity,
        negative_similarity: r.negative_similarity, name_score: r.name_score, term_score: r.term_score,
    }).collect()).unwrap_or_default();
    let verified = cache.all().filter(|p| p.hint.verification == VerificationState::DhtVerified).count();
    let clusters = cache.clusters(DEFAULT_CLUSTER_COUNT, cache.generation());
    let mut cluster_text = String::new();
    for cluster in clusters {
        let hashes: Vec<_> = cluster.exemplars.iter().map(|s| s.compact_hex()).collect();
        cluster_text.push_str(&format!("cluster {:02}: known={} exemplars=[{}] samples={}\n", cluster.cluster_id, cluster.known_count, hashes.join(", "), cluster.samples.len()));
    }
    let mut s = snapshot.lock().unwrap();
    s.recent = recent; s.search = search; s.profile_texts = docs.clone(); s.peers = peers.len(); s.verified = verified;
    s.gossip_sent = sent; s.gossip_received = received; s.cluster_text = cluster_text;
}

async fn network_worker(rx: mpsc::Receiver<Command>, snapshot: Arc<Mutex<Snapshot>>) -> Result<(), String> {
    let app = connect_app(&snapshot).await?;
    let main_dht = app.local_user().identity.as_str().to_string();
    let store = ensure_profile_store(&app).await.map_err(|e| e.to_string())?;
    app.advanced_client().register_app_root(&store.record_key).await.map_err(|e| e.to_string())?;
    {
        let mut s = snapshot.lock().unwrap(); s.main_dht = main_dht.clone(); s.profile_root = store.record_key.clone(); s.status = "Connected; profile gossip + DHT/blob verification active".into();
    }

    let mut cache = KnowledgeCache::default();
    let mut records: HashMap<String, ProfilePageRecord> = HashMap::new();
    let mut docs: HashMap<String, String> = HashMap::new();
    let mut known_peers: Vec<String> = Vec::new();
    let mut gossip_sent = 0u64; let mut gossip_received = 0u64; let mut active_intent: Option<DiscoveryIntent> = None;
    let mut next_request_id = now(); let mut query_reply_at = HashMap::new();

    if let Ok(read) = app.read_store(&store.store_id, &[PROFILE_SUBKEY], false).await {
        if let Some(bytes) = read.values.first().and_then(|v| v.value().ok()).flatten() {
            if let Ok(record) = serde_json::from_slice::<ProfilePageRecord>(&bytes) {
                if record.main_dht == main_dht {
                    if let Some(text) = fetch_profile_document(&app, &record).await { docs.insert(main_dht.clone(), text); }
                    cache.upsert(record.to_hint(now(), VerificationState::DhtVerified), Some("self"), now()); records.insert(main_dht.clone(), record);
                }
            }
        }
    }

    let mut messages = app.subscribe().await.map_err(|e| e.to_string())?;
    let (incoming_tx, mut incoming_rx) = tokio::sync::mpsc::channel(256);
    tokio::spawn(async move { loop { match messages.next().await { Ok(message) => if incoming_tx.send(message).await.is_err() { break; }, Err(_) => break } } });
    let mut peer_tick = tokio::time::interval(Duration::from_secs(PEER_REFRESH_SECS));
    let mut gossip_tick = tokio::time::interval(Duration::from_secs(GOSSIP_INTERVAL_SECS));
    let mut ui_tick = tokio::time::interval(Duration::from_millis(500));

    loop {
        tokio::select! {
            _ = peer_tick.tick() => refresh_peers(&app, &mut known_peers, &mut cache, &mut records, &mut docs, &snapshot).await,
            _ = gossip_tick.tick() => gossip_sent += gossip_summary(&app, &known_peers, &cache, &snapshot).await,
            _ = ui_tick.tick() => {
                while let Ok(command) = rx.try_recv() {
                    match command {
                        Command::Publish { profile_text, name, description, features_text } => match publish_profile(&app, &store, &main_dht, profile_text, name, description, features_text, records.get(&main_dht)).await {
                            Ok((record, text)) => {
                                docs.insert(main_dht.clone(), text); records.insert(main_dht.clone(), record.clone()); cache.upsert(record.to_hint(now(), VerificationState::DhtVerified), Some("self"), now());
                                snapshot.lock().unwrap().status = format!("Published profile generation {}", record.generation);
                                let announce = GossipMessage::ProfileAnnounce { protocol_version: PROTOCOL_VERSION, profile: record.to_hint(now(), VerificationState::GossipHint) };
                                if let Ok(bytes) = encode_gossip(&announce) { for peer in known_peers.iter().take(6) { if let Ok(identity) = NetworkIdentity::parse(peer.clone()) { if app.gossip(&identity, &bytes).await.is_ok() { gossip_sent += 1; } } } }
                            }
                            Err(e) => snapshot.lock().unwrap().log(format!("publish failed: {e}")),
                        },
                        Command::Search { name_query, positive_terms, positive_main_dht, negative_main_dht, avoidance_strength, common_term_penalty, stuffing_penalty, novelty_weight } => {
                            let intent = build_intent(&cache, name_query, positive_terms, positive_main_dht, negative_main_dht, avoidance_strength, common_term_penalty, stuffing_penalty, novelty_weight);
                            active_intent = Some(intent.clone()); next_request_id = next_request_id.wrapping_add(1);
                            let query = GossipMessage::SimilarityQuery { protocol_version: PROTOCOL_VERSION, request_id: next_request_id, intent, limit: 20 };
                            if let Ok(bytes) = encode_gossip(&query) { for peer in known_peers.iter().take(12) { if let Ok(identity) = NetworkIdentity::parse(peer.clone()) { if app.gossip(&identity, &bytes).await.is_ok() { gossip_sent += 1; } } } }
                        },
                        Command::Refresh => refresh_peers(&app, &mut known_peers, &mut cache, &mut records, &mut docs, &snapshot).await,
                        Command::GossipNow => gossip_sent += gossip_summary(&app, &known_peers, &cache, &snapshot).await,
                        Command::Stop => return Ok(()),
                    }
                }
                emit_snapshots(&cache, &docs, &main_dht, &known_peers, &active_intent, gossip_sent, gossip_received, &snapshot);
            },
            Some(message) = incoming_rx.recv() => {
                if message.delivery_kind != "gossip" { continue; }
                gossip_received += 1;
                match decode_gossip(&message.payload) {
                    Ok(gossip) => {
                        let source = message.sender.as_str().to_string();
                        process_gossip(&app, gossip, &source, &mut cache, &mut records, &mut docs, &known_peers, &mut query_reply_at, &snapshot).await;
                        let candidates: Vec<String> = cache.recent().iter().map(|p| p.hint.main_dht.clone()).filter(|p| p != &main_dht).take(64).collect();
                        if !candidates.is_empty() { let _ = app.advanced_client().recommend_nodes(&candidates, Some("veilysocial_profile_gossip"), 600).await; }
                    }
                    Err(error) => snapshot.lock().unwrap().log(format!("bad gossip: {error}")),
                }
            }
        }
    }
}
