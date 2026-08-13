# VeilySocial ProfilePageRecord v1

JSON stored at app-root subkey 0:

```json
{
  "schema_version": 1,
  "main_dht": "VLD0:...",
  "profile_root_dht": "VLD0:...",
  "generation": 1,
  "updated_at": 0,
  "name": "Alice",
  "description": "Woodworking, networking and electronics",
  "features": ["woodworking", "joinery", "rust"],
  "profile_blob_id": "...",
  "profile_blob_root": "VLD0:...",
  "profile_sha256_hex": "...",
  "profile_bytes": 12345,
  "vspf_version": 3
}
```

`name` is searchable but deliberately excluded from MinHash. `description` and `features` are discovery metadata and need not duplicate the visible text/layout inside the VSPF document.

`profile_blob_id` is mainly useful to the owner for deleting the old blob after a successful new generation. Remote clients locate the page through `profile_blob_root` and verify `profile_sha256_hex`/`profile_bytes`.
