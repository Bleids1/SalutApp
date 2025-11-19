package com.example.salutapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
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

    private lateinit var healthPermissionLauncher: ActivityResultLauncher<Set<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        healthPermissionLauncher = registerForActivityResult(viewModel.healthConnectManager.permissionLauncher) { permissions ->
            viewModel.updateHealthPermission(permissions.isNotEmpty())
        }

        setContent {
            SalutAppTheme {
                WeatherScreen(
                    viewModel = viewModel,
                    onGetHealthPermissions = { healthPermissionLauncher.launch(viewModel.healthConnectManager.requestPermissions()) },
                    onSkipHealthPermissions = { viewModel.skipHealthPermission() }
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
fun WeatherScreen(viewModel: WeatherViewModel, onGetHealthPermissions: () -> Unit, onSkipHealthPermissions: () -> Unit) {
    val hasLocationPermission = viewModel.hasLocationPermission.collectAsState().value
    val healthPermissionsGranted = viewModel.healthPermissionsGranted.collectAsState().value
    val healthPermissionSkipped = viewModel.healthPermissionSkipped.collectAsState().value
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
                        modifier = Modifier.height(40.dp) // Ajuste de logo
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
                val showMainContent = healthPermissionsGranted || healthPermissionSkipped
                if (showMainContent) {
                    when (weatherState) {
                        is WeatherState.Loading -> CircularProgressIndicator()
                        is WeatherState.Success -> {
                            val weatherData = weatherState.weatherData
                            WeatherCard(weatherData)
                            Spacer(modifier = Modifier.height(16.dp))
                            if (healthPermissionsGranted) {
                                WearableDataCard(wearableData = wearableState)
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                            WellnessTipCard(weatherData = weatherData, wearableData = if(healthPermissionsGranted) wearableState else null)
                            Spacer(modifier = Modifier.height(16.dp))
                            CompanyWebsiteButton()
                        }
                        is WeatherState.Error -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = weatherState.message,
                                    color = MaterialTheme.colorScheme.error,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                weatherState.retryAction?.let { retryAction ->
                                    OutlinedButton(onClick = { retryAction() }) {
                                        Text("Tentar Novamente")
                                    }
                                }
                            }
                        }
                    }
                } else {
                    HealthPermissionScreen(onGetHealthPermissions, viewModel.healthConnectManager.sdkStatus, context, onSkipHealthPermissions)
                }
            } else {
                Text(text = "Permissão de localização negada.")
            }
        }
    }
}

@Composable
fun HealthPermissionScreen(onGetHealthPermissions: () -> Unit, availability: Int, context: Context, onSkip: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxHeight()) {
        Text("Conecte seus dados de saúde para dicas personalizadas.", textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(16.dp))
        when (availability) {
            HealthConnectClient.SDK_AVAILABLE -> {
                Button(onClick = onGetHealthPermissions) {
                    Text("Conectar")
                }
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                Text("É necessário atualizar o Health Connect para continuar.", textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, "market://details?id=com.google.android.apps.healthdata".toUri())
                    context.startActivity(intent)
                }) {
                    Text("Atualizar")
                }
            }
            else -> {
                Text("O Health Connect não está disponível neste dispositivo.", textAlign = TextAlign.Center)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onSkip) {
            Text("Pular por enquanto")
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
                Text(text = "Passos de Hoje", fontSize = 20.sp)
                Text(text = "${wearableData.steps}")
            }
        }
    }
}

@Composable
fun WellnessTipCard(weatherData: WeatherData, wearableData: WearableData?) {
    val temp = weatherData.current.temp
    val condition = weatherData.current.condition.text.lowercase()

    val tip = if (wearableData != null) {
        // Dicas para quem conectou o wearable
        val sleptEnough = wearableData.sleepMinutes >= 420 // 7 hours
        val steps = wearableData.steps
        when {
            !sleptEnough && steps < 3000 ->
                "Parece que a noite foi curta e o dia, mais parado. Que tal um banho relaxante e uma noite de sono reparadora? Para ajudar a revitalizar a pele cansada, experimente nossa máscara de argila Kaolin."
            !sleptEnough ->
                "Uma boa noite de sono faz milagres pela pele. Como a sua foi mais curta, que tal dar uma forcinha com a nossa máscara de argila Kaolin? Ela renova e purifica."
            temp > 25 && steps > 8000 ->
                "Uau, você está com tudo! Com tanto movimento nesse calor, não se esqueça de se refrescar. Nosso lenço Glace é perfeito para dar aquele alívio imediato e revitalizar a pele."
            steps < 5000 && !condition.contains("chuva") ->
                "Você descansou, e o dia está ótimo para um passeio! Movimentar o corpo ajuda a produzir colágeno, mas você pode dar um empurrãozinho extra com nosso estimulador de colágeno à base de óleo de algodão."
            condition.contains("chuva") ->
                "Um dia chuvoso é um convite para se cuidar em casa. Já que você descansou bem, que tal um ritual de spa com a máscara de argila Kaolin para purificar e acalmar a pele?"
            temp < 15 && sleptEnough ->
                "Você dormiu bem! Com esse friozinho, a pele pode ficar mais seca. É o momento ideal para experimentar nosso estimulador de colágeno e manter a pele hidratada e firme."
            else ->
                "Seus dados mostram um ótimo equilíbrio entre descanso e atividade. Continue assim! Cuidar de si é o melhor investimento que você faz."
        }
    } else {
        // Dicas apenas com base no clima
        when {
            temp > 25 ->
                "O calor pede cuidados extras! Para manter a pele fresca e revitalizada ao longo do dia, que tal experimentar nosso lenço refrescante Glace? É um alívio imediato e super prático."
            temp < 15 ->
                "Com o tempo mais frio, a pele tende a ressecar. É uma ótima oportunidade para reforçar a hidratação e a nutrição com nosso estimulador de colágeno à base de óleo de algodão."
            condition.contains("chuva") ->
                "Dia de chuva combina com um cuidado especial em casa. Transforme seu banheiro em um spa com nossa máscara de argila nanoencapsulada Kaolin. Ela limpa, renova e acalma a pele."
            else ->
                "Seja qual for o clima, cuidar de você é sempre uma boa ideia. Lembre-se de beber água e aproveite para fazer algo que te faz bem hoje!"
        }
    }

    Card {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
        val intent = Intent(Intent.ACTION_VIEW, "https://www.salutbio.com.br".toUri())
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
        WeatherScreen(WeatherViewModel(context.applicationContext as Application), onGetHealthPermissions = {}, onSkipHealthPermissions = {})
    }
}
