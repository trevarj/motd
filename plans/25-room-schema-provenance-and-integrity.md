# Room schema provenance and relational-integrity decision

Date: 2026-07-15
Audit mission: D1 from `plans/23-codebase-e2e-hardening-audit.md`

## Schema provenance

The application schema history was generated from detached temporary worktrees
using the Room compiler from each revision. Those revisions predate distribution
flavors, so their processor task is `:app:kspDebugKotlin` (the current tree uses
`:app:kspFossDebugKotlin`).

| Version | Source | Result |
| --- | --- | --- |
| 1 | `57a0b61` | Generated from the committed v1 entities. |
| 2 | `2f0166e` | Generated from the committed v2 entities. |
| 3 | `58d0bd7` | Generated from the committed v3 entities. |
| 4 | Reconstructed from `6ea8776` | No v4 database revision was committed. The v3-to-v5 commit defines v4 as v3 plus `obfsMode`, `proxyHost`, and `proxyPort`; generation used that entity shape without the v5-only `obfsLink`. |
| 5 | `6ea8776` | Generated from the committed v5 entities. |
| 6 | Current branch | Generated from the current application entities. |

`AvatarDatabase` has only version 1; its schema was generated from the current
unchanged entity. All snapshots live under `app/schemas/` and are Gradle inputs
and outputs through the Room Gradle plugin.

## Current integrity guarantees

- `buffers.networkId` references `networks.id` with `ON DELETE CASCADE`.
- `messages.bufferId` references `buffers.id` with `ON DELETE CASCADE`; Room's
  external-content FTS triggers keep `messages_fts` synchronized.
- Buffer identity is unique by `(networkId, name)`. Message deduplication is
  unique by buffer plus `dedupKey`, non-null `msgid`, or non-null `eventKey`.
- Buffer deletion explicitly removes members and reactions in the same Room
  transaction before the buffer/message cascade.
- Network deletion explicitly removes a root's local children, members,
  reactions, and users in one Room transaction. The unused raw `NetworkDao`
  delete entry point was removed so callers cannot bypass that cleanup.
- Network creation is application-deduplicated by role-specific identity. The
  singleton repository now serializes the read-then-insert boundary, preventing
  concurrent onboarding, notification, and settings paths from creating the
  same child or endpoint twice in this process.

## Orphan and duplicate inventory

Before any relationship migration, inspect a copied production database with:

```sql
SELECT n.id, n.parentId
FROM networks n
LEFT JOIN networks p ON p.id = n.parentId
WHERE n.parentId IS NOT NULL AND p.id IS NULL;

SELECT parentId, bouncerNetId, COUNT(*)
FROM networks
WHERE role = 'BOUNCER_CHILD'
GROUP BY parentId, bouncerNetId
HAVING COUNT(*) > 1;

SELECT m.bufferId, COUNT(*) FROM members m
LEFT JOIN buffers b ON b.id = m.bufferId
WHERE b.id IS NULL GROUP BY m.bufferId;

SELECT r.bufferId, COUNT(*) FROM reactions r
LEFT JOIN buffers b ON b.id = r.bufferId
WHERE b.id IS NULL GROUP BY r.bufferId;

SELECT u.networkId, COUNT(*) FROM users u
LEFT JOIN networks n ON n.id = u.networkId
WHERE n.id IS NULL GROUP BY u.networkId;
```

## Constraint decision

Do not add a v7 relationship migration in this slice. Adding foreign keys to
members, reactions, and users requires table rebuilds and an explicit orphan
repair policy. Adding a self-reference for network parents also requires a
product decision between deleting orphan children, preserving them as visible
orphans, or converting them to direct networks. A unique child-identity index
is riskier still: duplicate children can each own overlapping buffers and
message history, so selecting one row without a merge algorithm would discard
user data.

A future v7 mission may add these constraints only after real database copies
have been inventoried and the following behavior is approved and tested:

1. deterministically merge duplicate child networks, buffers, messages,
   members, reactions, and users without losing history;
2. define repair behavior for orphan bouncer children;
3. rebuild child tables after repairing orphans, with cascade behavior and
   indices on every foreign-key child column;
4. migrate v1 and every checked-in intermediate schema to v7, then validate
   deletion, FTS, deduplication, and rollback/recovery fixtures.

Until then, the existing transactional cleanup plus serialized repository
creation is non-destructive and matches current product behavior.
