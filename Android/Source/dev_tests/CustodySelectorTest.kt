package app.weave

fun main() {
    val peers = (1..15).map { "peer$it" }
    val proven = setOf("peer4", "peer8")
    val p = planGroupCustodyTargetsV2(peers, proven, { if (it == "peer1") 99 else 0 }, "event-one")
    check(p.initial.size == 3)
    check(p.standby.size == 3)
    check(p.total == 6)
    check(p.initial.take(2).toSet() == proven) { "verified group custodians should be selected first" }
    check(p.initial[2] == "peer1")
    check(p.initial.toSet().intersect(p.standby.toSet()).isEmpty())
    check(p.initial == planGroupCustodyTargetsV2(peers, proven, { if (it == "peer1") 99 else 0 }, "event-one").initial)
    val empty = planGroupCustodyTargetsV2(emptyList(), emptySet(), { 0 }, "x")
    check(empty.total == 0)
    val duplicates = planGroupCustodyTargetsV2(listOf("a", "a", "", "b"), emptySet(), { 0 }, "x")
    check(duplicates.total == 2)
    val caps = planGroupCustodyTargetsV2(peers, emptySet(), { 0 }, "x", 999, 999)
    check(caps.total == 6 && caps.standby.isEmpty())
    val zero = planGroupCustodyTargetsV2(peers, emptySet(), { 0 }, "x", 3, 0)
    check(zero.total == 0)
    println("7 custody selection checks passed")
}
