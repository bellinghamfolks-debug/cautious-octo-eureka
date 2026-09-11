package com.abdullah.visionbridge.capture

/** One announcement for one continuous outage, shared by pre-crop and post-crop checks. */
class VisualAvailability {
    private var unavailable=false
    @Synchronized fun missing():Boolean = if(unavailable) false else { unavailable=true;true }
    @Synchronized fun available() { unavailable=false }
}
