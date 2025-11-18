package com.example.salutapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient
import coil.compose.AsyncImage
import com.example.salutapp.network.WeatherData
import com.example.salutapp.ui.theme.SalutAppTheme
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class MainActivity : ComponentActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val viewModel: WeatherViewModel by viewModels()

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        when {
            permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false) ||
            permissions.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false) -> {
                viewModel.updateLocationPermission(true)
                getCurrentLocation()
            }
            else -> {
                viewModel.updateLocationPermission(false)
            }
        }
    }

    private val healthPermissionLauncher = registerForActivityResult(
        viewModel.healthConnectManager.permissionLauncher
    ) { permissions ->
        viewModel.updateHealthPermission(permissions.isNotEmpty())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setContent {
            SalutAppTheme {
                WeatherScreen(
                    viewModel = viewModel,
                    onGetHealthPermissions = { healthPermissionLauncher.launch(viewModel.healthConnectManager.requestPermissions()) }
                )
            }
        }

        locationPermissionRequest.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    @SuppressLint("MissingPermission")
    private fun getCurrentLocation() {
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location ->
                if (location != null) {
                    viewModel.fetchWeather(location.latitude, location.longitude)
                }
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeatherScreen(viewModel: WeatherViewModel, onGetHealthPermissions: () -> Unit) {
    val hasLocationPermission = viewModel.hasLocationPermission.collectAsState().value
    val healthPermissionsGranted = viewModel.healthPermissionsGranted.collectAsState().value
    val weatherState = viewModel.weatherState.collectAsState().value
    val wearableState = viewModel.wearableState.collectAsState().value
    val context = LocalContext.current

    LaunchedEffect(healthPermissionsGranted) {
        if (healthPermissionsGranted) {
            viewModel.readHealthData()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Image(
                        painter = painterResource(id = R.drawable.salut_logo),
                        contentDescription = "Salut Logo",
                        modifier = Modifier.height(32.dp)
                    )
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshWeather() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Atualizar")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (hasLocationPermission) {
                if (healthPermissionsGranted) {
                    when (weatherState) {
                        is WeatherState.Loading -> CircularProgressIndicator()
                        is WeatherState.Success -> {
                            val weatherData = weatherState.weatherData
                            WeatherCard(weatherData)
                            Spacer(modifier = Modifier.height(16.dp))
                            WearableDataCard(wearableData = wearableState)
                            Spacer(modifier = Modifier.height(16.dp))
                            WellnessTipCard(weatherData = weatherData, wearableData = wearableState)
                            Spacer(modifier = Modifier.height(16.dp))
                            CompanyWebsiteButton()
                        }
                        is WeatherState.Error -> Text(text = "Erro: ${weatherState.message}")
                    }
                } else {
                    HealthPermissionScreen(onGetHealthPermissions, viewModel.healthConnectManager.sdkStatus, context)
                }
            } else {
                Text(text = "Permissão de localização negada.")
            }
        }
    }
}

@Composable
fun HealthPermissionScreen(onGetHealthPermissions: () -> Unit, availability: Int, context: Context) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Conecte seus dados de saúde para dicas personalizadas.", textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(16.dp))
        if (availability == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) {
            Button(onClick = {
                val intent = Intent(Intent.ACTION_VIEW, "market://details?id=com.google.android.apps.healthdata".toUri())
                context.startActivity(intent)
            }) {
                Text("Instalar Health Connect")
            }
        } else {
            Button(onClick = onGetHealthPermissions) {
                Text("Conectar")
            }
        }
    }
}

@Composable
fun WeatherCard(weatherData: WeatherData) {
    Card {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = "https:${weatherData.current.condition.icon}",
                contentDescription = "Ícone do clima",
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(text = "${weatherData.current.temp}°C", fontSize = 32.sp)
                Text(text = weatherData.current.condition.text)
            }
        }
    }
}

@Composable
fun WearableDataCard(wearableData: WearableData) {
    val sleepHours = wearableData.sleepMinutes / 60
    val sleepMinutes = wearableData.sleepMinutes % 60

    Card {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceAround) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "Sono", fontSize = 20.sp)
                Text(text = "${sleepHours}h ${sleepMinutes}m")
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "Passos", fontSize = 20.sp)
                Text(text = "${wearableData.steps}")
            }
        }
    }
}

@Composable
fun WellnessTipCard(weatherData: WeatherData, wearableData: WearableData) {
    val temp = weatherData.current.temp
    val condition = weatherData.current.condition.text.lowercase()
    val sleptEnough = wearableData.sleepMinutes >= 420 // 7 hours
    val steps = wearableData.steps

    val tip = when {
        !sleptEnough && steps < 3000 ->
            "Você dormiu pouco e se moveu pouco. Considere uma caminhada leve hoje e uma boa noite de sono para recarregar as energias."
        !sleptEnough ->
            "Uma noite de sono curta pode afetar sua pele. Considere um momento relaxante com nossas máscaras faciais para revitalizar."
        steps < 5000 && !condition.contains("chuva") ->
            "Você descansou bem! Que tal aproveitar o dia para dar uma caminhada? Lembre-se do protetor solar."
        temp > 25 && steps > 10000 ->
            "Uau, mais de 10.000 passos no calor! Use nosso Glace para um alívio refrescante e ajude na recuperação."
        condition.contains("chuva") ->
            "Dia chuvoso, perfeito para relaxar. Já que você se movimentou bem ontem, que tal uma sessão de yoga em casa?"
        else ->
            "Você está indo bem! Continue mantendo o equilíbrio entre descanso e atividade. Um ótimo dia para cuidar de si."
    }

    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Dica de Bem-Estar", fontSize = 20.sp, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = tip, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun CompanyWebsiteButton() {
    val context = LocalContext.current
    Button(onClick = {
        val intent = Intent(Intent.ACTION_VIEW, "https://www.salut.com.br".toUri())
        context.startActivity(intent)
    }) {
        Text("Visite nosso site")
    }
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    SalutAppTheme {
        val context = LocalContext.current
        WeatherScreen(WeatherViewModel(context.applicationContext as Application), onGetHealthPermissions = {})
    }
}
