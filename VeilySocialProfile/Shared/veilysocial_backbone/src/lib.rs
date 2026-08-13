//! VeilySocial discovery backbone.
//!
//! This crate intentionally separates *fast hints* from *authoritative state*.
//! Gossip is lossy/untrusted and optimized for discovery. DHT profile records
//! are the confirmation path. The profile name is never included in MinHash
//! feature extraction.

use serde::{Deserialize, Deserializer, Serialize, Serializer};
use sha2::{Digest, Sha256};
use base64::{engine::general_purpose::STANDARD as BASE64, Engine as _};
use std::collections::{BTreeSet, HashMap, VecDeque};

pub const PROTOCOL_VERSION: u16 = 1;
pub const MINHASH_SIZE: usize = 64;
pub const MAX_FEATURES: usize = 64;
pub const MAX_RECENT_PROFILES: usize = 50;
pub const MAX_CACHE_PROFILES: usize = 2_000;
pub const DEFAULT_CLUSTER_COUNT: usize = 10;
pub const MAX_CLUSTER_COUNT: usize = 32;
pub const MAX_CLUSTER_EXEMPLARS: usize = 3;
pub const MAX_CLUSTER_SAMPLES: usize = 8;
pub const MAX_HINT_DESCRIPTION_CHARS: usize = 180;
pub const MAX_HINT_FEATURES: usize = 16;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct BusinessCardProfile {
    pub schema_version: u16,
    pub main_dht: String,
    pub profile_root_dht: String,
    pub generation: u64,
    pub updated_at: u64,
    pub name: String,
    pub description: String,
    pub features: Vec<String>,
    /// Small image encoded as base64. The sample app normalizes it to 50x70.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub logo_png_base64: Option<String>,
}

/// Network descriptor for a full customizable VeilySocial profile page.
///
/// The visual VSPF document itself lives in the daemon blob store because a
/// decorated profile can be far larger than one DHT subkey.  This compact
/// record is what lives in the app-root DHT and what gossip ultimately points
/// back to for authoritative confirmation.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ProfilePageRecord {
    pub schema_version: u16,
    pub main_dht: String,
    pub profile_root_dht: String,
    pub generation: u64,
    pub updated_at: u64,
    /// Search/display name. Deliberately excluded from MinHash.
    pub name: String,
    /// Search/discovery description; it need not duplicate visible page text.
    pub description: String,
    /// Explicit skills/interests/tags used by discovery.
    pub features: Vec<String>,
    /// Local daemon blob identifier; useful to retire the previous owned blob after an update.
    pub profile_blob_id: String,
    /// Root record key returned by the daemon blob store for the VSPF text envelope.
    pub profile_blob_root: String,
    /// SHA-256 of the exact UTF-8 VSPF text envelope.
    pub profile_sha256_hex: String,
    pub profile_bytes: u64,
    /// Current VSPF document format version expected by the authoring app.
    pub vspf_version: u16,
}

impl ProfilePageRecord {
    pub fn normalized_features(&self) -> Vec<String> {
        extract_features(&self.description, &self.features)
    }

    pub fn signature(&self) -> MinHashSignature {
        MinHashSignature::from_features(&self.normalized_features())
    }

    pub fn to_hint(&self, observed_at: u64, verification: VerificationState) -> ProfileHint {
        ProfileHint {
            main_dht: self.main_dht.clone(),
            profile_root_dht: self.profile_root_dht.clone(),
            generation: self.generation,
            updated_at: self.updated_at,
            observed_at,
            name: self.name.clone(),
            description: self.description.chars().take(MAX_HINT_DESCRIPTION_CHARS).collect(),
            features: self.features.iter().take(MAX_HINT_FEATURES).cloned().collect(),
            minhash: self.signature(),
            verification,
        }
    }
}

impl BusinessCardProfile {
    pub fn normalized_features(&self) -> Vec<String> {
        extract_features(&self.description, &self.features)
    }

    pub fn signature(&self) -> MinHashSignature {
        MinHashSignature::from_features(&self.normalized_features())
    }

    pub fn to_hint(&self, observed_at: u64, verification: VerificationState) -> ProfileHint {
        ProfileHint {
            main_dht: self.main_dht.clone(),
            profile_root_dht: self.profile_root_dht.clone(),
            generation: self.generation,
            updated_at: self.updated_at,
            observed_at,
            name: self.name.clone(),
            description: self.description.chars().take(MAX_HINT_DESCRIPTION_CHARS).collect(),
            features: self.features.iter().take(MAX_HINT_FEATURES).cloned().collect(),
            minhash: self.signature(),
            verification,
        }
    }
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, PartialOrd, Ord)]
#[serde(rename_all = "snake_case")]
pub enum VerificationState {
    GossipHint,
    MultiSourceHint,
    AppRootConfirmed,
    DhtVerified,
}

impl VerificationState {
    pub fn rank(self) -> u8 {
        match self {
            Self::GossipHint => 0,
            Self::MultiSourceHint => 1,
            Self::AppRootConfirmed => 2,
            Self::DhtVerified => 3,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ProfileHint {
    pub main_dht: String,
    pub profile_root_dht: String,
    pub generation: u64,
    pub updated_at: u64,
    pub observed_at: u64,
    pub name: String,
    pub description: String,
    pub features: Vec<String>,
    pub minhash: MinHashSignature,
    pub verification: VerificationState,
}

impl ProfileHint {
    pub fn normalized_features(&self) -> Vec<String> {
        extract_features(&self.description, &self.features)
    }
}

/// Lightweight rotating entry in a cluster's gossip record table. It is only
/// a discovery pointer; the app-root/DHT path must confirm the profile.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ProfileSampleRef {
    pub main_dht: String,
    pub profile_root_dht: String,
    pub generation: u64,
    pub updated_at: u64,
    pub name: String,
}

impl ProfileSampleRef {
    pub fn from_hint(hint: &ProfileHint) -> Self {
        Self {
            main_dht: hint.main_dht.clone(),
            profile_root_dht: hint.profile_root_dht.clone(),
            generation: hint.generation,
            updated_at: hint.updated_at,
            name: hint.name.clone(),
        }
    }

    pub fn approximate_hint(&self, observed_at: u64, cluster_signature: MinHashSignature) -> ProfileHint {
        ProfileHint {
            main_dht: self.main_dht.clone(),
            profile_root_dht: self.profile_root_dht.clone(),
            generation: self.generation,
            updated_at: self.updated_at,
            observed_at,
            name: self.name.clone(),
            description: String::new(),
            features: Vec::new(),
            minhash: cluster_signature,
            verification: VerificationState::GossipHint,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct MinHashSignature {
    /// 64-slot b-bit MinHash. Each slot stores the low 16 bits of the true
    /// minimum hash. The 1/65536 collision bias is negligible for discovery.
    /// JSON packs the 128 bytes as base64 so gossip summaries stay compact.
    #[serde(with = "minhash_wire")]
    pub values: Vec<u16>,
}

impl Default for MinHashSignature {
    fn default() -> Self {
        Self { values: vec![u16::MAX; MINHASH_SIZE] }
    }
}

impl MinHashSignature {
    pub fn from_features(features: &[String]) -> Self {
        if features.is_empty() {
            return Self::default();
        }
        let mut minima = vec![u64::MAX; MINHASH_SIZE];
        for feature in features {
            let base = stable_hash64(feature.as_bytes());
            for (index, slot) in minima.iter_mut().enumerate() {
                let seed = splitmix64((index as u64).wrapping_mul(0x9E37_79B9_7F4A_7C15));
                let value = splitmix64(base ^ seed);
                *slot = (*slot).min(value);
            }
        }
        Self { values: minima.into_iter().map(|value| (value & 0xFFFF) as u16).collect() }
    }

    pub fn similarity(&self, other: &Self) -> f32 {
        let count = self.values.len().min(other.values.len());
        if count == 0 {
            return 0.0;
        }
        let same = self.values.iter().zip(&other.values).take(count).filter(|(a, b)| a == b).count();
        same as f32 / count as f32
    }

    pub fn compact_hex(&self) -> String {
        let mut hasher = Sha256::new();
        for value in &self.values {
            hasher.update(value.to_le_bytes());
        }
        let bytes = hasher.finalize();
        bytes[..8].iter().map(|b| format!("{b:02x}")).collect()
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WeightedSignature {
    pub signature: MinHashSignature,
    pub weight: f32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DiscoveryIntent {
    #[serde(default)]
    pub positive: Vec<WeightedSignature>,
    #[serde(default)]
    pub negative: Vec<WeightedSignature>,
    /// Optional case-insensitive display-name query. This is intentionally
    /// separate from MinHash; profile names never enter the feature signature.
    #[serde(default)]
    pub name_query: Option<String>,
    #[serde(default)]
    pub positive_terms: Vec<String>,
    #[serde(default)]
    pub negative_terms: Vec<String>,
    /// 0 = ignore negative examples, 1 = normal avoidance, >1 = aggressive.
    pub avoidance_strength: f32,
    /// Penalty strength for very common matched terms.
    pub common_term_penalty: f32,
    /// Penalty strength for repeated/broad keyword stuffing signals.
    pub stuffing_penalty: f32,
    /// Reward for candidates less similar to results already selected.
    pub novelty_weight: f32,
}

impl Default for DiscoveryIntent {
    fn default() -> Self {
        Self {
            positive: Vec::new(),
            negative: Vec::new(),
            name_query: None,
            positive_terms: Vec::new(),
            negative_terms: Vec::new(),
            avoidance_strength: 0.75,
            common_term_penalty: 0.20,
            stuffing_penalty: 0.25,
            novelty_weight: 0.10,
        }
    }
}

#[derive(Debug, Clone)]
pub struct ScoredProfile {
    pub hint: ProfileHint,
    pub score: f32,
    pub positive_similarity: f32,
    pub negative_similarity: f32,
    pub name_score: f32,
    pub term_score: f32,
    pub common_penalty: f32,
    pub stuffing_penalty: f32,
    pub novelty_bonus: f32,
}

#[derive(Debug, Clone, Default)]
pub struct CorpusStats {
    documents: usize,
    document_frequency: HashMap<String, usize>,
}

impl CorpusStats {
    pub fn rebuild<'a>(&mut self, profiles: impl IntoIterator<Item = &'a ProfileHint>) {
        self.documents = 0;
        self.document_frequency.clear();
        for profile in profiles {
            self.documents += 1;
            let unique: BTreeSet<_> = profile.normalized_features().into_iter().collect();
            for term in unique {
                *self.document_frequency.entry(term).or_default() += 1;
            }
        }
    }

    pub fn commonness(&self, term: &str) -> f32 {
        if self.documents == 0 {
            return 0.0;
        }
        self.document_frequency.get(term).copied().unwrap_or_default() as f32 / self.documents as f32
    }

    pub fn rarity(&self, term: &str) -> f32 {
        1.0 - self.commonness(term)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClusterDigest {
    pub cluster_id: u32,
    pub exemplars: Vec<MinHashSignature>,
    pub known_count: usize,
    pub samples: Vec<ProfileSampleRef>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum GossipMessage {
    Summary {
        protocol_version: u16,
        generation: u64,
        created_at: u64,
        clusters: Vec<ClusterDigest>,
    },
    ProfileAnnounce {
        protocol_version: u16,
        profile: ProfileHint,
    },
    SimilarityQuery {
        protocol_version: u16,
        request_id: u64,
        intent: DiscoveryIntent,
        limit: usize,
    },
    SimilarityResponse {
        protocol_version: u16,
        request_id: u64,
        samples: Vec<ProfileHint>,
    },
}

impl GossipMessage {
    pub fn protocol_version(&self) -> u16 {
        match self {
            Self::Summary { protocol_version, .. }
            | Self::ProfileAnnounce { protocol_version, .. }
            | Self::SimilarityQuery { protocol_version, .. }
            | Self::SimilarityResponse { protocol_version, .. } => *protocol_version,
        }
    }
}

#[derive(Debug, Clone)]
pub struct CachedProfile {
    pub hint: ProfileHint,
    pub first_seen_at: u64,
    pub last_seen_at: u64,
    pub source_count: usize,
}

#[derive(Debug, Clone, Default)]
pub struct KnowledgeCache {
    profiles: HashMap<String, CachedProfile>,
    recent: VecDeque<String>,
    stats: CorpusStats,
    generation: u64,
}

impl KnowledgeCache {
    pub fn generation(&self) -> u64 { self.generation }
    pub fn len(&self) -> usize { self.profiles.len() }
    pub fn is_empty(&self) -> bool { self.profiles.is_empty() }

    pub fn get(&self, main_dht: &str) -> Option<&CachedProfile> {
        self.profiles.get(main_dht)
    }

    pub fn all(&self) -> impl Iterator<Item = &CachedProfile> {
        self.profiles.values()
    }

    pub fn upsert(&mut self, mut hint: ProfileHint, source_id: Option<&str>, now: u64) -> bool {
        hint.observed_at = now.max(hint.observed_at);
        let key = hint.main_dht.clone();
        let mut changed = false;
        match self.profiles.get_mut(&key) {
            Some(existing) => {
                if hint.generation > existing.hint.generation
                    || hint.verification.rank() > existing.hint.verification.rank()
                    || hint.updated_at > existing.hint.updated_at
                {
                    if hint.generation >= existing.hint.generation {
                        existing.hint = hint;
                    } else {
                        existing.hint.verification = existing.hint.verification.max(hint.verification);
                    }
                    changed = true;
                }
                existing.last_seen_at = now;
                if source_id.is_some() {
                    existing.source_count = existing.source_count.saturating_add(1).min(255);
                    if existing.source_count >= 2 && existing.hint.verification == VerificationState::GossipHint {
                        existing.hint.verification = VerificationState::MultiSourceHint;
                    }
                }
            }
            None => {
                self.profiles.insert(key.clone(), CachedProfile {
                    hint,
                    first_seen_at: now,
                    last_seen_at: now,
                    source_count: usize::from(source_id.is_some()),
                });
                changed = true;
            }
        }

        self.touch_recent(&key);
        self.trim_cache();
        if changed {
            self.generation = self.generation.wrapping_add(1);
            self.rebuild_stats();
        }
        changed
    }

    pub fn recent(&self) -> Vec<&CachedProfile> {
        self.recent.iter().filter_map(|key| self.profiles.get(key)).take(MAX_RECENT_PROFILES).collect()
    }

    pub fn clusters(&self, desired: usize, generation_seed: u64) -> Vec<ClusterDigest> {
        let profiles: Vec<&ProfileHint> = self.profiles.values().map(|cached| &cached.hint).collect();
        cluster_profiles(&profiles, desired, generation_seed)
    }

    pub fn search(&self, intent: &DiscoveryIntent, limit: usize) -> Vec<ScoredProfile> {
        rank_profiles(self.profiles.values().map(|c| &c.hint), &self.stats, intent, limit)
    }

    fn touch_recent(&mut self, key: &str) {
        if let Some(position) = self.recent.iter().position(|value| value == key) {
            self.recent.remove(position);
        }
        self.recent.push_front(key.to_string());
        while self.recent.len() > MAX_RECENT_PROFILES {
            self.recent.pop_back();
        }
    }

    fn trim_cache(&mut self) {
        if self.profiles.len() <= MAX_CACHE_PROFILES {
            return;
        }
        let mut by_age: Vec<_> = self.profiles.iter().map(|(key, value)| (key.clone(), value.last_seen_at)).collect();
        by_age.sort_by_key(|(_, seen)| *seen);
        for (key, _) in by_age.into_iter().take(self.profiles.len() - MAX_CACHE_PROFILES) {
            self.profiles.remove(&key);
            if let Some(position) = self.recent.iter().position(|value| value == &key) {
                self.recent.remove(position);
            }
        }
    }

    fn rebuild_stats(&mut self) {
        self.stats.rebuild(self.profiles.values().map(|cached| &cached.hint));
    }
}

pub fn extract_features(description: &str, declared: &[String]) -> Vec<String> {
    let mut set = BTreeSet::new();
    for feature in declared.iter().take(MAX_FEATURES) {
        for token in tokenize(feature) {
            if token.len() >= 2 { set.insert(token); }
        }
    }
    for token in tokenize(description) {
        if token.len() >= 3 && !STOP_WORDS.contains(&token.as_str()) {
            set.insert(token);
        }
        if set.len() >= MAX_FEATURES { break; }
    }
    set.into_iter().take(MAX_FEATURES).collect()
}

pub fn tokenize(text: &str) -> Vec<String> {
    text.to_lowercase()
        .split(|c: char| !c.is_alphanumeric() && c != '-' && c != '_')
        .filter(|s| !s.is_empty())
        .map(|s| s.trim_matches(|c: char| c == '-' || c == '_').to_string())
        .filter(|s| !s.is_empty())
        .collect()
}

pub fn stuffing_signal(description: &str, declared: &[String]) -> f32 {
    let raw: Vec<String> = declared.iter().flat_map(|s| tokenize(s)).chain(tokenize(description)).collect();
    if raw.is_empty() { return 0.0; }
    let unique: BTreeSet<&String> = raw.iter().collect();
    let repeat_ratio = 1.0 - unique.len() as f32 / raw.len() as f32;
    let breadth = ((unique.len().saturating_sub(28)) as f32 / 72.0).clamp(0.0, 1.0);
    (repeat_ratio * 0.65 + breadth * 0.35).clamp(0.0, 1.0)
}

pub fn rank_profiles<'a>(
    profiles: impl IntoIterator<Item = &'a ProfileHint>,
    stats: &CorpusStats,
    intent: &DiscoveryIntent,
    limit: usize,
) -> Vec<ScoredProfile> {
    let positive_terms: Vec<_> = intent.positive_terms.iter().flat_map(|term| tokenize(term)).collect();
    let negative_terms: Vec<_> = intent.negative_terms.iter().flat_map(|term| tokenize(term)).collect();
    let mut scored = Vec::new();

    for hint in profiles {
        let features = hint.normalized_features();
        let feature_set: BTreeSet<_> = features.iter().map(String::as_str).collect();

        let positive_similarity = intent.positive.iter()
            .map(|target| hint.minhash.similarity(&target.signature) * target.weight.max(0.0))
            .fold(0.0_f32, f32::max);
        let negative_similarity = intent.negative.iter()
            .map(|target| hint.minhash.similarity(&target.signature) * target.weight.max(0.0))
            .fold(0.0_f32, f32::max);

        let name_score = intent.name_query.as_deref().map(str::trim).filter(|q| !q.is_empty())
            .map(|query| {
                let query = query.to_lowercase();
                let name = hint.name.to_lowercase();
                if name == query { 1.50 }
                else if name.starts_with(&query) { 1.00 }
                else if name.contains(&query) { 0.55 }
                else { 0.0 }
            }).unwrap_or(0.0);

        let mut term_score = 0.0;
        let mut matched_commonness = Vec::new();
        for term in &positive_terms {
            if feature_set.contains(term.as_str()) {
                let rarity = stats.rarity(term);
                term_score += 0.20 + 0.80 * rarity;
                matched_commonness.push(stats.commonness(term));
            }
        }
        for term in &negative_terms {
            if feature_set.contains(term.as_str()) {
                term_score -= 1.0;
            }
        }
        if !positive_terms.is_empty() {
            term_score /= positive_terms.len() as f32;
        }
        let common_penalty = if matched_commonness.is_empty() { 0.0 } else {
            matched_commonness.iter().sum::<f32>() / matched_commonness.len() as f32
        } * intent.common_term_penalty.max(0.0);
        let stuffing_penalty = stuffing_signal(&hint.description, &hint.features) * intent.stuffing_penalty.max(0.0);

        let score = positive_similarity
            + name_score
            + term_score
            - negative_similarity * intent.avoidance_strength.max(0.0)
            - common_penalty
            - stuffing_penalty;
        scored.push(ScoredProfile {
            hint: hint.clone(),
            score,
            positive_similarity,
            negative_similarity,
            name_score,
            term_score,
            common_penalty,
            stuffing_penalty,
            novelty_bonus: 0.0,
        });
    }

    scored.sort_by(|a, b| b.score.total_cmp(&a.score).then_with(|| b.hint.updated_at.cmp(&a.hint.updated_at)));

    // Greedy diversity pass. Already-selected near-duplicates receive less of
    // the novelty reward, which gives adjacent clusters a chance to appear.
    if intent.novelty_weight > 0.0 {
        let mut selected: Vec<ScoredProfile> = Vec::new();
        let mut pool = scored;
        while !pool.is_empty() && selected.len() < limit {
            let mut best_index = 0;
            let mut best_adjusted = f32::NEG_INFINITY;
            for (index, candidate) in pool.iter().enumerate() {
                let max_seen_similarity = selected.iter()
                    .map(|chosen| candidate.hint.minhash.similarity(&chosen.hint.minhash))
                    .fold(0.0_f32, f32::max);
                let novelty = (1.0 - max_seen_similarity) * intent.novelty_weight;
                let adjusted = candidate.score + novelty;
                if adjusted > best_adjusted {
                    best_adjusted = adjusted;
                    best_index = index;
                }
            }
            let mut chosen = pool.remove(best_index);
            let max_seen_similarity = selected.iter()
                .map(|other| chosen.hint.minhash.similarity(&other.hint.minhash))
                .fold(0.0_f32, f32::max);
            chosen.novelty_bonus = (1.0 - max_seen_similarity) * intent.novelty_weight;
            chosen.score += chosen.novelty_bonus;
            selected.push(chosen);
        }
        selected
    } else {
        scored.into_iter().take(limit).collect()
    }
}

pub fn cluster_profiles(profiles: &[&ProfileHint], desired: usize, seed: u64) -> Vec<ClusterDigest> {
    if profiles.is_empty() { return Vec::new(); }
    let k = desired.clamp(1, MAX_CLUSTER_COUNT).min(profiles.len());

    // Farthest-first medoid seeds give good coverage without expensive k-means.
    let mut medoids = vec![stable_index_seed(profiles, seed)];
    while medoids.len() < k {
        let mut best = None;
        for (index, profile) in profiles.iter().enumerate() {
            if medoids.contains(&index) { continue; }
            let nearest = medoids.iter()
                .map(|&m| profile.minhash.similarity(&profiles[m].minhash))
                .fold(1.0_f32, f32::min);
            let distance = 1.0 - nearest;
            if best.map_or(true, |(_, best_distance)| distance > best_distance) {
                best = Some((index, distance));
            }
        }
        let Some((index, _)) = best else { break; };
        medoids.push(index);
    }

    let mut groups: Vec<Vec<usize>> = vec![Vec::new(); medoids.len()];
    for (index, profile) in profiles.iter().enumerate() {
        let (group, _) = medoids.iter().enumerate()
            .map(|(group, &medoid)| (group, profile.minhash.similarity(&profiles[medoid].minhash)))
            .max_by(|a, b| a.1.total_cmp(&b.1)).unwrap();
        groups[group].push(index);
    }

    groups.into_iter().enumerate().filter_map(|(cluster_id, members)| {
        if members.is_empty() { return None; }
        let medoid_index = choose_medoid(profiles, &members);
        let mut exemplar_indexes = vec![medoid_index];
        let mut ordered = members.clone();
        ordered.sort_by(|a, b| {
            profiles[*a].minhash.similarity(&profiles[medoid_index].minhash)
                .total_cmp(&profiles[*b].minhash.similarity(&profiles[medoid_index].minhash))
        });
        for index in ordered {
            if exemplar_indexes.len() >= MAX_CLUSTER_EXEMPLARS { break; }
            if !exemplar_indexes.contains(&index) { exemplar_indexes.push(index); }
        }

        let known_count = members.len();
        let mut sample_indexes = members;
        sample_indexes.sort_by_key(|index| splitmix64(seed ^ stable_hash64(profiles[*index].main_dht.as_bytes())));
        let samples = sample_indexes.into_iter().take(MAX_CLUSTER_SAMPLES)
            .map(|index| ProfileSampleRef::from_hint(profiles[index])).collect();

        Some(ClusterDigest {
            cluster_id: cluster_id as u32,
            exemplars: exemplar_indexes.into_iter().map(|index| profiles[index].minhash.clone()).collect(),
            known_count,
            samples,
        })
    }).collect()
}

fn choose_medoid(profiles: &[&ProfileHint], members: &[usize]) -> usize {
    if members.len() <= 1 { return members[0]; }
    // Cap work for large clusters by evaluating at most 48 deterministic members.
    let candidates = members.iter().take(48).copied().collect::<Vec<_>>();
    candidates.into_iter().max_by(|a, b| {
        let score_a: f32 = members.iter().take(96).map(|other| profiles[*a].minhash.similarity(&profiles[*other].minhash)).sum();
        let score_b: f32 = members.iter().take(96).map(|other| profiles[*b].minhash.similarity(&profiles[*other].minhash)).sum();
        score_a.total_cmp(&score_b)
    }).unwrap_or(members[0])
}

fn stable_index_seed(profiles: &[&ProfileHint], seed: u64) -> usize {
    profiles.iter().enumerate()
        .min_by_key(|(_, profile)| splitmix64(seed ^ stable_hash64(profile.main_dht.as_bytes())))
        .map(|(index, _)| index).unwrap_or(0)
}

pub fn merge_intent_from_seed_profiles(query_terms: &[String], positive: &[ProfileHint], negative: &[ProfileHint]) -> DiscoveryIntent {
    DiscoveryIntent {
        positive: positive.iter().map(|hint| WeightedSignature { signature: hint.minhash.clone(), weight: 1.0 }).collect(),
        negative: negative.iter().map(|hint| WeightedSignature { signature: hint.minhash.clone(), weight: 1.0 }).collect(),
        name_query: None,
        positive_terms: query_terms.to_vec(),
        ..DiscoveryIntent::default()
    }
}

pub fn debug_snapshot(cache: &KnowledgeCache) -> String {
    let recent = cache.recent();
    let clusters = cache.clusters(DEFAULT_CLUSTER_COUNT, cache.generation());
    let mut out = String::new();
    out.push_str(&format!("VeilySocial backbone v{}\n", PROTOCOL_VERSION));
    out.push_str(&format!("known_profiles={} generation={} recent={}\n", cache.len(), cache.generation(), recent.len()));
    for cluster in &clusters {
        let hashes: Vec<_> = cluster.exemplars.iter().map(MinHashSignature::compact_hex).collect();
        out.push_str(&format!("cluster={} known~{} exemplars={} samples={}\n", cluster.cluster_id, cluster.known_count, hashes.join(","), cluster.samples.len()));
    }
    out
}

pub fn encode_gossip(message: &GossipMessage) -> Result<Vec<u8>, serde_json::Error> {
    serde_json::to_vec(message)
}

pub fn decode_gossip(bytes: &[u8]) -> Result<GossipMessage, serde_json::Error> {
    serde_json::from_slice(bytes)
}

mod minhash_wire {
    use super::*;
    use serde::de::Error as _;

    pub fn serialize<S>(values: &Vec<u16>, serializer: S) -> Result<S::Ok, S::Error>
    where S: Serializer {
        let mut bytes = Vec::with_capacity(values.len() * 2);
        for value in values { bytes.extend_from_slice(&value.to_le_bytes()); }
        serializer.serialize_str(&BASE64.encode(bytes))
    }

    pub fn deserialize<'de, D>(deserializer: D) -> Result<Vec<u16>, D::Error>
    where D: Deserializer<'de> {
        let encoded = String::deserialize(deserializer)?;
        let bytes = BASE64.decode(encoded).map_err(D::Error::custom)?;
        if bytes.len() != MINHASH_SIZE * 2 {
            return Err(D::Error::custom(format!("expected {} MinHash bytes, got {}", MINHASH_SIZE * 2, bytes.len())));
        }
        Ok(bytes.chunks_exact(2).map(|chunk| u16::from_le_bytes([chunk[0], chunk[1]])).collect())
    }
}

fn stable_hash64(bytes: &[u8]) -> u64 {
    // FNV-1a followed by SplitMix avalanche; stable across platforms/languages.
    let mut hash = 0xcbf29ce484222325u64;
    for byte in bytes {
        hash ^= *byte as u64;
        hash = hash.wrapping_mul(0x100000001b3);
    }
    splitmix64(hash)
}

fn splitmix64(mut x: u64) -> u64 {
    x = x.wrapping_add(0x9E3779B97F4A7C15);
    x = (x ^ (x >> 30)).wrapping_mul(0xBF58476D1CE4E5B9);
    x = (x ^ (x >> 27)).wrapping_mul(0x94D049BB133111EB);
    x ^ (x >> 31)
}

const STOP_WORDS: &[&str] = &[
    "the", "and", "for", "that", "with", "this", "from", "have", "your", "you", "are", "was", "were", "but", "not", "all", "can", "about", "into", "our", "out", "too", "use", "using", "some", "more", "very", "just", "like", "what", "when", "where", "who", "why", "how",
];

#[cfg(test)]
mod tests {
    use super::*;

    fn hint(id: &str, words: &[&str]) -> ProfileHint {
        let features = words.iter().map(|s| s.to_string()).collect::<Vec<_>>();
        ProfileHint {
            main_dht: format!("VLD0:{id}"),
            profile_root_dht: format!("VLD0:root-{id}"),
            generation: 1,
            updated_at: 1,
            observed_at: 1,
            name: id.to_string(),
            description: String::new(),
            minhash: MinHashSignature::from_features(&features),
            features,
            verification: VerificationState::GossipHint,
        }
    }

    #[test]
    fn minhash_wire_vector_matches_android() {
        let features = ["woodworking", "joinery", "oak", "furniture"]
            .into_iter().map(str::to_string).collect::<Vec<_>>();
        let signature = MinHashSignature::from_features(&features);
        assert_eq!(signature.compact_hex(), "c34a7180275a2834");
        let json = serde_json::to_value(&signature).unwrap();
        assert_eq!(json["values"], "zulz7TP60Pr9UF1kRv7iXIDo1LqCpglA2YVJu0B8SfMBM4WsHehsxt5Jom2pU0+IfLdWjzmY8VnQzDnfXY1g1EfCgqqlBX/C6/4QyS5RLuj2OIALzbBQSW9NJEP29U8EV7xOOUlhjDSS2BKsZPc7g/NavxrW5/YdyWBLtI2mXsY=");
    }

    #[test]
    fn similar_sets_score_above_unrelated_sets() {
        let a = hint("a", &["woodworking", "joinery", "oak", "furniture"]);
        let b = hint("b", &["woodworking", "joinery", "walnut", "furniture"]);
        let c = hint("c", &["astronomy", "telescope", "planets", "stars"]);
        assert!(a.minhash.similarity(&b.minhash) > a.minhash.similarity(&c.minhash));
    }

    #[test]
    fn negative_example_reduces_score() {
        let good = hint("good", &["woodworking", "joinery", "furniture"]);
        let ad = hint("ad", &["woodworking", "tool-sale", "advertisement"]);
        let intent = DiscoveryIntent {
            positive: vec![WeightedSignature { signature: good.minhash.clone(), weight: 1.0 }],
            negative: vec![WeightedSignature { signature: ad.minhash.clone(), weight: 1.0 }],
            avoidance_strength: 1.0,
            ..DiscoveryIntent::default()
        };
        let mut stats = CorpusStats::default();
        stats.rebuild([&good, &ad]);
        let results = rank_profiles([&good, &ad], &stats, &intent, 2);
        assert_eq!(results[0].hint.main_dht, good.main_dht);
    }

    #[test]
    fn name_query_is_separate_from_minhash_and_ranks_matches() {
        let bob = hint("bob", &["astronomy", "telescope"]);
        let alice = hint("alice", &["woodworking", "joinery"]);
        let intent = DiscoveryIntent { name_query: Some("bo".to_string()), ..DiscoveryIntent::default() };
        let mut stats = CorpusStats::default();
        stats.rebuild([&bob, &alice]);
        let results = rank_profiles([&alice, &bob], &stats, &intent, 2);
        assert_eq!(results[0].hint.main_dht, bob.main_dht);
        assert!(results[0].name_score > 0.0);
    }

    #[test]
    fn recent_list_is_bounded_to_fifty() {
        let mut cache = KnowledgeCache::default();
        for i in 0..75 {
            cache.upsert(hint(&format!("{i}"), &["test"]), Some("source"), i);
        }
        assert_eq!(cache.recent().len(), 50);
    }
}
