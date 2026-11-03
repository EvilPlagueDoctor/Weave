// ============================================================================
// Protocol constants and default policy
// ============================================================================


/// A bounded seven-day public-witness window applies ONLY to Weave group intake
/// and group event-witness service identifiers. All other ServiceRequests,
/// including widgets, retain the existing one-hour cap.
pub const GROUP_WITNESS_TTL_SECS: u64 = 7 * 24 * 60 * 60;
const WEAVE_GROUP_WITNESS_SERVICE_IDS: [[u8; 32]; 4] = [
    [0xce, 0x56, 0xcc, 0xbf, 0x9b, 0x94, 0x25, 0x16, 0x9c, 0x0c, 0x7b, 0x1d, 0xd4, 0x7e, 0x54, 0x9f, 0x52, 0x0f, 0x99, 0x57, 0x42, 0x45, 0xdb, 0x01, 0xcc, 0x73, 0x68, 0x69, 0x6c, 0x99, 0xe8, 0x31],
    [0xbf, 0x32, 0xc4, 0xa2, 0x33, 0xdf, 0xd2, 0x60, 0xa3, 0x83, 0x80, 0xba, 0x17, 0x05, 0x2d, 0xfe, 0x5c, 0x56, 0x4a, 0xe5, 0xe5, 0x97, 0x33, 0x4e, 0xf3, 0x76, 0x60, 0x40, 0x35, 0x81, 0x78, 0x1d],
    [0xaf, 0x49, 0xbf, 0xd9, 0x84, 0x15, 0x68, 0xae, 0x95, 0xaf, 0x29, 0xc5, 0xbe, 0x2b, 0x0b, 0x06, 0xea, 0xf9, 0xaf, 0x4d, 0xa4, 0xd6, 0x4f, 0xbf, 0xd8, 0x24, 0x2d, 0xd8, 0x83, 0xdc, 0xd5, 0xf5],
    [0xec, 0xcc, 0xe8, 0x0a, 0x43, 0xc9, 0xa9, 0x55, 0x6f, 0xf8, 0x61, 0xb8, 0x07, 0x0b, 0x73, 0xb9, 0x9d, 0x0c, 0x89, 0xb0, 0x68, 0x05, 0x5b, 0x63, 0x43, 0x67, 0x44, 0x4c, 0x27, 0x29, 0xf4, 0x97],
];

pub fn service_request_ttl_cap_secs(service_id: &[u8; 32], ordinary_cap_secs: u64) -> u64 {
    if WEAVE_GROUP_WITNESS_SERVICE_IDS.contains(service_id) {
        GROUP_WITNESS_TTL_SECS
    } else {
        ordinary_cap_secs
    }
}

pub const MAILBOX_PROTOCOL_VERSION: u16 = 1;
pub const MAILBOX_STORE_VERSION: u32 = 1;
pub const MAILBOX_STORE_KEY: &str = "mailbox_state_v1";
pub const MAILBOX_TRANSACTION_STORE_KEY: &str = "mailbox_transactions_v1";
pub const MAILBOX_QUOTA_STORE_KEY: &str = "mailbox_quota_state_v1";

pub const INDEX_SLOT_A: u32 = 0;
pub const INDEX_SLOT_B: u32 = 1;
pub const FIRST_DATA_SUBKEY: u32 = 2;

pub const MAILBOX_DHT_NAME: &str = "mailbox_custodian";
pub const MAILSEND_DHT_NAME: &str = "mail_send";
pub const MAILRESPONSE_DHT_NAME: &str = "mail_response";
pub const OVERFLOW_DHT_NAME_PREFIX: &str = "mailbox_overflow";

/// Mailbox page records deliberately use 64 subkeys.
///
/// Veilid divides a record's roughly 1 MiB value budget across its schema
/// subkeys. A 1000-subkey record therefore permits only about 1 KiB per value,
/// which is too small for an encrypted message page. Sixty-four subkeys leave
/// roughly 16 KiB per value while still providing 62 copy-on-write data slots
/// after the two A/B index slots.
pub const PAGED_DHT_GROUPS: [u16; 1] = [64];
pub const PAGED_DHT_TOTAL_SUBKEYS: u32 = 64;

const RECEIVE_KDF_DOMAIN: &[u8] = b"network-walk/mailbox/receive-key/v1";
const MESSAGE_KEY_DOMAIN: &[u8] = b"network-walk/mailbox/message-key/v1";
const MESSAGE_ID_DOMAIN: &[u8] = b"network-walk/mailbox/message-id/v1";
const MESSAGE_SIGNATURE_DOMAIN: &[u8] = b"network-walk/mailbox/message-signature/v1";
const BUMP_SIGNATURE_DOMAIN: &[u8] = b"network-walk/mailbox/bump-signature/v1";
const RESPONSE_SIGNATURE_DOMAIN: &[u8] = b"network-walk/mailbox/response-signature/v1";
const SERVICE_REQUEST_ID_DOMAIN: &[u8] = b"network-walk/mailbox/service-request-id/v1";
const SERVICE_REQUEST_SIGNATURE_DOMAIN: &[u8] = b"network-walk/mailbox/service-request-signature/v1";

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MailboxConfig {
    pub participate_as_custodian: bool,
    pub page_target_size: usize,
    pub page_split_threshold: usize,
    pub complete_message_max_size: usize,
    pub max_plaintext_size: usize,
    /// Public service requests are deliberately much smaller/shorter-lived than private mail.
    pub max_service_request_size: usize,
    pub max_service_payload_size: usize,
    pub max_service_reply_route_size: usize,
    pub default_service_request_ttl_secs: u64,
    pub max_service_request_ttl_secs: u64,
    pub active_service_requests_per_sender: usize,
    pub active_service_requests_per_host: usize,
    pub recent_service_request_cache: usize,
    pub service_request_pointer_checks_per_walk: usize,
    pub normal_batch_interval_secs: u64,
    pub maintenance_interval_secs: u64,
    pub early_flush_queue_size: usize,
    pub dht_io_concurrency: usize,
    pub max_navigation_suggestions: usize,
    pub advertised_previous_key_epochs: usize,
    pub max_timestamp_skew_secs: u64,
    pub max_message_age_secs: u64,
    pub max_requested_expiry_secs: u64,
    pub minimum_bump_interval_secs: u64,
    pub active_messages_per_sender: usize,
    pub active_messages_per_sender_recipient: usize,
    pub active_messages_per_recipient: usize,
    pub active_messages_per_overflow_record: usize,
    pub candidate_messages_per_walk: usize,
    pub mailsend_pages_per_walk: usize,
    pub mailbox_pages_per_walk: usize,
    pub pending_validation_limit: usize,
    pub recipient_inline_pointer_limit: usize,
    pub recipient_inline_byte_limit: usize,
    pub overflow_demote_pointer_limit: usize,
    pub overflow_demote_byte_limit: usize,
    pub overflow_demote_hysteresis_secs: u64,
    pub max_overflow_dhts: usize,
    pub response_check_base_secs: u64,
    pub response_check_max_secs: u64,
    pub response_retention_secs: u64,
    pub close_grace_secs: u64,
    pub receive_region_prefix_bits: u16,
    pub retention_weights: RetentionWeights,
}

impl Default for MailboxConfig {
    fn default() -> Self {
        Self {
            participate_as_custodian: true,
            // A 64-subkey DHT has approximately 16 KiB available per value.
            // Keep pages comfortably below that ceiling for schema/encoding
            // overhead and future format growth.
            page_target_size: 12 * 1024,
            page_split_threshold: 14 * 1024,
            complete_message_max_size: 10 * 1024,
            max_plaintext_size: 8 * 1024,
            max_service_request_size: 8 * 1024,
            max_service_payload_size: 1024,
            max_service_reply_route_size: 4 * 1024,
            default_service_request_ttl_secs: 15 * 60,
            max_service_request_ttl_secs: 60 * 60,
            active_service_requests_per_sender: 32,
            active_service_requests_per_host: 8,
            recent_service_request_cache: 1024,
            service_request_pointer_checks_per_walk: 24,
            normal_batch_interval_secs: 120,
            maintenance_interval_secs: 30 * 60,
            early_flush_queue_size: 128,
            dht_io_concurrency: 128,
            max_navigation_suggestions: 12,
            advertised_previous_key_epochs: 64,
            max_timestamp_skew_secs: 15 * 60,
            max_message_age_secs: 365 * 24 * 60 * 60,
            max_requested_expiry_secs: 180 * 24 * 60 * 60,
            minimum_bump_interval_secs: 24 * 60 * 60,
            active_messages_per_sender: 256,
            active_messages_per_sender_recipient: 32,
            active_messages_per_recipient: 4_096,
            active_messages_per_overflow_record: 1_000,
            candidate_messages_per_walk: 128,
            mailsend_pages_per_walk: 16,
            mailbox_pages_per_walk: 24,
            pending_validation_limit: 2_048,
            recipient_inline_pointer_limit: 48,
            recipient_inline_byte_limit: 9 * 1024,
            overflow_demote_pointer_limit: 24,
            overflow_demote_byte_limit: 6 * 1024,
            overflow_demote_hysteresis_secs: 14 * 24 * 60 * 60,
            max_overflow_dhts: 256,
            response_check_base_secs: 15 * 60,
            response_check_max_secs: 7 * 24 * 60 * 60,
            response_retention_secs: 180 * 24 * 60 * 60,
            close_grace_secs: 7 * 24 * 60 * 60,
            receive_region_prefix_bits: 12,
            retention_weights: RetentionWeights::default(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RetentionWeights {
    pub recent_message: f32,
    pub recent_bump: f32,
    pub distance: f32,
    pub sender_reputation: f32,
    pub repeated_verification: f32,
    pub under_replication: f32,
    pub age_penalty: f32,
    pub storage_cost_penalty: f32,
    pub sender_quota_penalty: f32,
    pub replication_penalty: f32,
}

impl Default for RetentionWeights {
    fn default() -> Self {
        Self {
            recent_message: 30.0,
            recent_bump: 10.0,
            distance: 20.0,
            sender_reputation: 20.0,
            repeated_verification: 8.0,
            under_replication: 12.0,
            age_penalty: 35.0,
            storage_cost_penalty: 12.0,
            sender_quota_penalty: 25.0,
            replication_penalty: 10.0,
        }
    }
}

// ============================================================================

#[cfg(test)]
mod weave_group_witness_ttl_tests {
    use super::{service_request_ttl_cap_secs, GROUP_WITNESS_TTL_SECS, WEAVE_GROUP_WITNESS_SERVICE_IDS};

    #[test]
    fn only_group_witness_ids_receive_seven_days() {
        for id in WEAVE_GROUP_WITNESS_SERVICE_IDS {
            assert_eq!(service_request_ttl_cap_secs(&id, 3600), GROUP_WITNESS_TTL_SECS);
        }
        assert_eq!(service_request_ttl_cap_secs(&[0_u8; 32], 3600), 3600);
    }
}
