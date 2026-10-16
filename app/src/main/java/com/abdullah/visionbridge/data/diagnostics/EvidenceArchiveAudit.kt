package com.abdullah.visionbridge.data.diagnostics

/**
 * Checks that every frame an event points at is in the archive.
 *
 * A reference may name a frame inside a video (`inputs-0001.mp4#t=3.250`); it is satisfied by the
 * file. A reference to a file that is gone for a stated reason — lost to a process death, deleted
 * by the user, left out to keep the bundle under its size limit — is [accounted], not missing.
 */
object EvidenceArchiveAudit {
    data class Result(val missing: List<String>, val colliding: List<String>, val accounted: List<String> = emptyList()) {
        val valid: Boolean get() = missing.isEmpty() && colliding.isEmpty()
    }

    fun check(files: List<String>, references: List<String>, accountedFor: Collection<String> = emptyList()): Result {
        val inventory = files.toSet()
        val explained = accountedFor.toSet()
        val absent = references.distinct().filter { EvidenceVideoPlan.referencedFile(it) !in inventory }
        return Result(
            missing = absent.filter { EvidenceVideoPlan.referencedFile(it) !in explained },
            colliding = references.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.toList() +
                files.groupingBy { it }.eachCount().filterValues { it > 1 }.keys,
            accounted = absent.filter { EvidenceVideoPlan.referencedFile(it) in explained },
        )
    }
}
