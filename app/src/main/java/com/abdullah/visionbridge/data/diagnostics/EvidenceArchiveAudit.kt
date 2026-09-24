package com.abdullah.visionbridge.data.diagnostics

object EvidenceArchiveAudit {
    data class Result(val missing:List<String>,val colliding:List<String>) {
        val valid:Boolean get()=missing.isEmpty()&&colliding.isEmpty()
    }
    fun check(files:List<String>,references:List<String>):Result {
        val inventory=files.toSet()
        return Result(references.distinct().filter { it !in inventory },
            references.groupingBy { it }.eachCount().filterValues { it>1 }.keys.toList()+
                files.groupingBy { it }.eachCount().filterValues { it>1 }.keys)
    }
}
