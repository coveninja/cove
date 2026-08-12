package com.coveninja.cove.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class LibraryStatus(val wireName: String) {
    @SerialName("watch_later") WatchLater("watch_later"),
    @SerialName("watching")    Watching("watching"),
    @SerialName("finished")    Finished("finished"),
    @SerialName("dropped")     Dropped("dropped"),
}
