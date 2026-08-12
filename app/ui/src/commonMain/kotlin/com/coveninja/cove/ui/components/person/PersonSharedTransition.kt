package com.coveninja.cove.ui.components.person

/** The parts of a person that morph between a cast card and the person sheet. */
public enum class PersonSharedPart {
    Container,
    Portrait,
}

public data class PersonSharedKey(
    val personId: String,
    val part: PersonSharedPart,
)
