package com.example.salutapp

import android.app.Application
import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.salutapp.network.Condition
import com.example.salutapp.network.Current
import com.example.salutapp.network.WeatherData
import com.example.salutapp.network.WeatherApi
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.Instant
import java.time.Duration

data class CachedWeatherData(
    val weatherData: WeatherData,
    val timestamp: Instant,
    val lat: Double,
    val lon: Double
) {
    fun isExpired(maxAgeMinutes: Long = 30): Boolean {
        return Duration.between(timestamp, Instant.now()).toMinutes() > maxAgeMinutes
    }
}

sealed class WeatherState {
    object Loading : WeatherState()
    data class Success(val weatherData: WeatherData) : WeatherState()
    data class Error(val message: String, val retryAction: (() -> Unit)? = null) : WeatherState()
}

sealed class WearableState {
    object Loading : WearableState()
    data class Success(val data: WearableData) : WearableState()
    data class Error(val message: String) : WearableState()
}

class WeatherViewModel(application: Application) : AndroidViewModel(application) {
    // Para teste: Defina uma temperatura aqui ou deixe como null para usar a API
    private val testTemperature: Double? = null

    private val weatherApiKey = BuildConfig.WEATHER_API_KEY

    private val _hasLocationPermission = MutableStateFlow(false)
    val hasLocationPermission = _hasLocationPermission.asStateFlow()

    private val _weatherState = MutableStateFlow<WeatherState>(WeatherState.Loading)
    val weatherState = _weatherState.asStateFlow()

    private val _wearableState = MutableStateFlow(WearableData(0, 0))
    val wearableState = _wearableState.asStateFlow()

    private val _healthPermissionsGranted = MutableStateFlow(false)
    val healthPermissionsGranted = _healthPermissionsGranted.asStateFlow()

    private val _healthPermissionSkipped = MutableStateFlow(false)
    val healthPermissionSkipped = _healthPermissionSkipped.asStateFlow()

    private var lastLat: Double? = null
    private var lastLon: Double? = null

    private val weatherApi: WeatherApi
    private val sharedPrefs = application.getSharedPreferences("weather_cache", Context.MODE_PRIVATE)
    private val gson = Gson()
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
            _healthPermissionSkipped.value = false // Reseta o "skip" se a permissão for concedida
            readHealthData()
        }
    }

    fun skipHealthPermission() {
        _healthPermissionSkipped.value = true
    }

    fun fetchWeather(lat: Double, lon: Double) {
        if (testTemperature != null) {
            setTestWeather(testTemperature)
            return
        }

        // Valida coordenadas
        if (!isValidCoordinates(lat, lon)) {
            _weatherState.value = WeatherState.Error("Coordenadas inválidas")
            return
        }

        lastLat = lat
        lastLon = lon

        // Verifica cache primeiro
        val cachedData = getCachedWeather()
        if (cachedData != null &&
            !cachedData.isExpired() &&
            cachedData.lat == lat &&
            cachedData.lon == lon) {
            // Cache válido encontrado
            _weatherState.value = WeatherState.Success(cachedData.weatherData)
            return
        } else if (cachedData != null &&
                   cachedData.lat == lat &&
                   cachedData.lon == lon &&
                   !cachedData.isExpired()) {
            // Cache ainda válido, mas mostraremos um estado "atualizando" em background
            _weatherState.value = WeatherState.Success(cachedData.weatherData)
        } else {
            _weatherState.value = WeatherState.Loading
        }

        // Busca dados frescos
        fetchFreshWeather(lat, lon)
    }

    private fun fetchFreshWeather(lat: Double, lon: Double) {
        viewModelScope.launch {
            try {
                val weatherData = weatherApi.getWeather(weatherApiKey, "$lat,$lon")

                // Valida dados recebidos da API
                if (isValidWeatherData(weatherData)) {
                    // Salva no cache
                    saveWeatherToCache(weatherData, lat, lon)
                    _weatherState.value = WeatherState.Success(weatherData)
                } else {
                    // Tenta usar cache como fallback
                    val cachedData = getCachedWeather()
                    if (cachedData != null && cachedData.lat == lat && cachedData.lon == lon) {
                        _weatherState.value = WeatherState.Success(cachedData.weatherData)
                    } else {
                        _weatherState.value = WeatherState.Error("Dados do clima inválidos", { fetchFreshWeather(lat, lon) })
                    }
                }
            } catch (e: retrofit2.HttpException) {
                val errorMessage = when (e.code()) {
                    401 -> "Chave da API inválida"
                    403 -> "Acesso negado à API"
                    404 -> "Localização não encontrada"
                    429 -> "Muitas requisições. Tente novamente em alguns minutos"
                    500, 502, 503 -> "Servidor indisponível. Tente novamente"
                    else -> "Erro de conexão: ${e.code()}"
                }

                // Tenta usar cache como fallback
                val cachedData = getCachedWeather()
                if (cachedData != null && cachedData.lat == lat && cachedData.lon == lon) {
                    _weatherState.value = WeatherState.Success(cachedData.weatherData)
                } else {
                    _weatherState.value = WeatherState.Error(errorMessage, { fetchFreshWeather(lat, lon) })
                }
            } catch (e: java.net.UnknownHostException) {
                // Tenta usar cache como fallback para modo offline
                val cachedData = getCachedWeather()
                if (cachedData != null) {
                    _weatherState.value = WeatherState.Success(cachedData.weatherData)
                } else {
                    _weatherState.value = WeatherState.Error("Sem conexão com a internet", { fetchFreshWeather(lat, lon) })
                }
            } catch (e: java.net.SocketTimeoutException) {
                _weatherState.value = WeatherState.Error("Timeout da conexão. Verifique sua internet", { fetchFreshWeather(lat, lon) })
            } catch (e: Exception) {
                _weatherState.value = WeatherState.Error("Erro inesperado: ${e.localizedMessage ?: "Erro desconhecido"}", { fetchFreshWeather(lat, lon) })
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
        if (_healthPermissionsGranted.value) {
            readHealthData()
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

    private fun isValidCoordinates(lat: Double, lon: Double): Boolean {
        return lat in -90.0..90.0 && lon in -180.0..180.0
    }

    private fun isValidWeatherData(weatherData: WeatherData): Boolean {
        return weatherData.current.temp in -100.0..100.0 && // Temperatura razoável
               weatherData.current.condition.text.isNotBlank() &&
               weatherData.current.condition.icon.isNotBlank()
    }

    private fun saveWeatherToCache(weatherData: WeatherData, lat: Double, lon: Double) {
        try {
            val cachedWeatherData = CachedWeatherData(weatherData, Instant.now(), lat, lon)
            val json = gson.toJson(cachedWeatherData)
            sharedPrefs.edit()
                .putString("cached_weather", json)
                .apply()
        } catch (e: Exception) {
            // Falha silenciosa no cache - não deve afetar funcionalidade principal
        }
    }

    private fun getCachedWeather(): CachedWeatherData? {
        return try {
            val json = sharedPrefs.getString("cached_weather", null)
            if (json != null) {
                gson.fromJson(json, CachedWeatherData::class.java)
            } else {
                null
            }
        } catch (e: Exception) {
            // Retorna null se houver erro no cache
            null
        }
    }
}
