package com.example.salutapp.network

import com.google.gson.annotations.SerializedName

data class WeatherData(
    val current: Current
)

data class Current(
    @SerializedName("temp_c")
    val temp: Double,
    val condition: Condition
)

data class Condition(
    val text: String,
    val icon: String
)