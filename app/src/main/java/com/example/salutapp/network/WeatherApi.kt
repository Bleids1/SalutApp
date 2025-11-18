package com.example.salutapp.network

import retrofit2.http.GET
import retrofit2.http.Query

interface WeatherApi {
    @GET("current.json")
    suspend fun getWeather(
        @Query("key") key: String,
        @Query("q") latLon: String
    ): WeatherData
}