package com.example.salutapp

import android.app.Application
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.salutapp.network.Condition
import com.example.salutapp.network.Current
import com.example.salutapp.network.WeatherData
import com.example.salutapp.network.WeatherApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

sealed class WeatherState {
    object Loading : WeatherState()
    data class Success(val weatherData: WeatherData) : WeatherState()
    data class Error(val message: String) : WeatherState()
}

class WeatherViewModel(application: Application) : AndroidViewModel(application) {
    // Para teste: Defina uma temperatura aqui ou deixe como null para usar a API
    private val testTemperature: Double? = null

    private val _hasLocationPermission = MutableStateFlow(false)
    val hasLocationPermission = _hasLocationPermission.asStateFlow()

    private val _weatherState = MutableStateFlow<WeatherState>(WeatherState.Loading)
    val weatherState = _weatherState.asStateFlow()

    private val _wearableState = MutableStateFlow(WearableData(0, 0))
    val wearableState = _wearableState.asStateFlow()

    private val _healthPermissionsGranted = MutableStateFlow(false)
    val healthPermissionsGranted = _healthPermissionsGranted.asStateFlow()

    private var lastLat: Double? = null
    private var lastLon: Double? = null

    private val weatherApi: WeatherApi
    val healthConnectManager = HealthConnectManager(application)

    init {
        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.weatherapi.com/v1/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        weatherApi = retrofit.create(WeatherApi::class.java)

        viewModelScope.launch {
            _healthPermissionsGranted.value = healthConnectManager.hasAllPermissions()
        }
    }

    fun updateLocationPermission(hasPermission: Boolean) {
        _hasLocationPermission.value = hasPermission
    }

    fun updateHealthPermission(granted: Boolean) {
        _healthPermissionsGranted.value = granted
        if (granted) {
            readHealthData()
        }
    }

    fun fetchWeather(lat: Double, lon: Double) {
        if (testTemperature != null) {
            setTestWeather(testTemperature)
            return
        }

        lastLat = lat
        lastLon = lon
        viewModelScope.launch {
            _weatherState.value = WeatherState.Loading
            try {
                val weatherData = weatherApi.getWeather("e22ecbb305554d8bb4e150616251811", "$lat,$lon")
                _weatherState.value = WeatherState.Success(weatherData)
            } catch (e: Exception) {
                _weatherState.value = WeatherState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun refreshWeather() {
        if (testTemperature != null) {
            setTestWeather(testTemperature)
            return
        }
        lastLat?.let { lat ->
            lastLon?.let { lon ->
                fetchWeather(lat, lon)
            }
        }
    }

    fun readHealthData() {
        viewModelScope.launch {
            healthConnectManager.readHealthData()
                .catch { e -> /* Tratar erros de leitura */ }
                .collect { data -> _wearableState.value = data }
        }
    }

    private fun setTestWeather(temp: Double) {
        val testCondition = Condition("Ensolarado (Teste)", "//cdn.weatherapi.com/weather/64x64/day/113.png")
        val testCurrent = Current(temp, testCondition)
        val testWeatherData = WeatherData(testCurrent)
        _weatherState.value = WeatherState.Success(testWeatherData)
    }
}