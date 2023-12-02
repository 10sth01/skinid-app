package com.example.skinidchatbot2

data class Condition(
    val causes: List<Cause>,
    val description: String,
    val symptoms: List<Symptom>,
    val treatment: List<Treatment>,
)

data class Cause(val cause: String)

data class Symptom(val symptom: String)

data class Treatment(val treatment: String)

data class Description(val description: String)

