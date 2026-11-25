package cz.olik.obsidianaudio

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay

private val AmoledBlack = Color(0xFF000000)
private val SurfaceDark = Color(0xFF0C0C0E)
private val BorderSubtle = Color(0xFF1F1F23)
private val AccentMint = Color(0xFF00E5A0)
private val TextPrimary = Color(0xFFF5F5F7)
private val TextSecondary = Color(0xFF8E8E93)

private val AmoledColorScheme = darkColorScheme(
    primary = AccentMint,
    onPrimary = AmoledBlack,
    background = AmoledBlack,
    surface = AmoledBlack,
    surfaceContainer = SurfaceDark,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    outlineVariant = BorderSubtle
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK)
        )
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(colorScheme = AmoledColorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = AmoledBlack
                ) {
                    MusicPlayerApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicPlayerApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, audioPermission) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    // Oznámení (Android 13+): bez něj se nezobrazí ovládání přehrávače v notifikacích a na zamykací obrazovce
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(audioPermission)
        }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!notifGranted) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            tracks = AudioRepository.loadTracks(context)
        }
    }

    var controller by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            controller = future.get()
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            MediaController.releaseFuture(future)
        }
    }

    var isPlaying by remember { mutableStateOf(false) }
    var currentMediaId by remember { mutableStateOf<String?>(null) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var shuffleEnabled by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableIntStateOf(Player.REPEAT_MODE_ALL) }
    var showFullPlayer by remember { mutableStateOf(false) }

    DisposableEffect(controller) {
        val player = controller ?: return@DisposableEffect onDispose {}
        val syncState = {
            isPlaying = player.isPlaying
            currentMediaId = player.currentMediaItem?.mediaId
            durationMs = player.duration.coerceAtLeast(0L)
            currentPositionMs = player.currentPosition.coerceAtLeast(0L)
            shuffleEnabled = player.shuffleModeEnabled
            repeatMode = player.repeatMode
        }
        syncState()

        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                syncState()
            }
            override fun onPlaybackStateChanged(state: Int) {
                syncState()
            }
            override fun onShuffleModeEnabledChanged(enabled: Boolean) {
                shuffleEnabled = enabled
            }
            override fun onRepeatModeChanged(mode: Int) {
                repeatMode = mode
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Aktualizace času běží POUZE když hraje hudba A aplikace je viditelná
    LaunchedEffect(controller, isPlaying, lifecycleOwner) {
        val player = controller ?: return@LaunchedEffect
        if (!isPlaying) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                currentPositionMs = player.currentPosition.coerceAtLeast(0L)
                durationMs = player.duration.coerceAtLeast(0L)
                delay(500L)
            }
        }
    }

    val filteredTracks = remember(tracks, searchQuery) {
        if (searchQuery.isBlank()) tracks
        else tracks.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
                it.artist.contains(searchQuery, ignoreCase = true)
        }
    }

    val activeTrack = remember(tracks, currentMediaId) {
        tracks.find { it.id.toString() == currentMediaId }
    }

    Scaffold(
        containerColor = AmoledBlack,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Obsidian",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "${tracks.size} skladeb",
                            style = MaterialTheme.typography.labelSmall,
                            color = AccentMint
                        )
                    }
                    Row {
                        IconButton(onClick = {
                            isSearchActive = !isSearchActive
                            if (!isSearchActive) searchQuery = ""
                        }) {
                            Icon(
                                imageVector = if (isSearchActive) Icons.Rounded.Close else Icons.Rounded.Search,
                                contentDescription = "Hledat",
                                tint = TextPrimary
                            )
                        }
                        IconButton(onClick = {
                            val player = controller
                            if (player != null && tracks.isNotEmpty()) {
                                player.shuffleModeEnabled = true
                                player.setMediaItems(tracks.map { it.mediaItem })
                                player.prepare()
                                player.play()
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Rounded.Shuffle,
                                contentDescription = "Zamíchat vše",
                                tint = AccentMint
                            )
                        }
                    }
                }

                if (isSearchActive) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("Název nebo interpret...", color = TextSecondary) },
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentMint,
                            unfocusedBorderColor = BorderSubtle,
                            focusedContainerColor = SurfaceDark,
                            unfocusedContainerColor = SurfaceDark
                        )
                    )
                }
            }
        },
        bottomBar = {
            if (activeTrack != null) {
                MiniPlayerBar(
                    track = activeTrack,
                    isPlaying = isPlaying,
                    progress = if (durationMs > 0) (currentPositionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
                    onTogglePlay = {
                        controller?.let { if (it.isPlaying) it.pause() else it.play() }
                    },
                    onNext = { controller?.seekToNextMediaItem() },
                    onExpand = { showFullPlayer = true }
                )
            }
        }
    ) { innerPadding ->
        if (!hasPermission) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Button(
                    onClick = { permissionLauncher.launch(audioPermission) },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentMint)
                ) {
                    Text("Povolit přístup k hudbě", color = AmoledBlack, fontWeight = FontWeight.SemiBold)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                itemsIndexed(
                    items = filteredTracks,
                    key = { _, track -> track.id }
                ) { _, track ->
                    val isCurrent = track.id.toString() == currentMediaId
                    TrackRow(
                        track = track,
                        isCurrent = isCurrent,
                        onClick = {
                            val player = controller
                            if (player != null) {
                                val targetIndex = tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
                                player.setMediaItems(tracks.map { it.mediaItem }, targetIndex, 0L)
                                player.prepare()
                                player.play()
                            }
                        }
                    )
                }
            }
        }
    }

    if (showFullPlayer && activeTrack != null) {
        ModalBottomSheet(
            onDismissRequest = { showFullPlayer = false },
            containerColor = AmoledBlack,
            dragHandle = { BottomSheetDefaults.DragHandle(color = BorderSubtle) }
        ) {
            FullPlayerSheet(
                track = activeTrack,
                isPlaying = isPlaying,
                positionMs = currentPositionMs,
                durationMs = durationMs,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
                onSeek = { newPos ->
                    currentPositionMs = newPos
                    controller?.seekTo(newPos)
                },
                onTogglePlay = {
                    controller?.let { if (it.isPlaying) it.pause() else it.play() }
                },
                onPrevious = { controller?.seekToPreviousMediaItem() },
                onNext = { controller?.seekToNextMediaItem() },
                onToggleShuffle = {
                    controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
                },
                onCycleRepeat = {
                    controller?.let {
                        it.repeatMode = when (it.repeatMode) {
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
                            else -> Player.REPEAT_MODE_ALL
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun TrackRow(
    track: Track,
    isCurrent: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isCurrent) SurfaceDark else AmoledBlack)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(track.artworkUri)
                .size(96)
                .crossfade(false)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceDark)
        )

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                color = if (isCurrent) AccentMint else TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Text(
            text = formatTime(track.durationMs),
            style = MaterialTheme.typography.labelSmall,
            color = if (isCurrent) AccentMint else TextSecondary,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
private fun MiniPlayerBar(
    track: Track,
    isPlaying: Boolean,
    progress: Float,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onExpand: () -> Unit
) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onExpand),
        shape = RoundedCornerShape(16.dp),
        color = SurfaceDark,
        border = BorderStroke(1.dp, BorderSubtle)
    ) {
        Column {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = AccentMint,
                trackColor = BorderSubtle
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(track.artworkUri)
                        .size(96)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(AmoledBlack)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onTogglePlay) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = "Přehrát / Pauza",
                        tint = AccentMint,
                        modifier = Modifier.size(30.dp)
                    )
                }
                IconButton(onClick = onNext) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = "Další",
                        tint = TextPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun FullPlayerSheet(
    track: Track,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(track.artworkUri)
                .size(512)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(240.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(SurfaceDark)
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = track.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = track.artist,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(20.dp))

        Slider(
            value = if (durationMs > 0) positionMs.toFloat().coerceIn(0f, durationMs.toFloat()) else 0f,
            onValueChange = { onSeek(it.toLong()) },
            valueRange = 0f..(durationMs.coerceAtLeast(1L).toFloat()),
            colors = SliderDefaults.colors(
                thumbColor = AccentMint,
                activeTrackColor = AccentMint,
                inactiveTrackColor = BorderSubtle
            )
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(formatTime(positionMs), style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Text(formatTime(durationMs), style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onToggleShuffle) {
                Icon(
                    imageVector = Icons.Rounded.Shuffle,
                    contentDescription = "Náhodně",
                    tint = if (shuffleEnabled) AccentMint else TextSecondary
                )
            }
            IconButton(onClick = onPrevious, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.Rounded.SkipPrevious,
                    contentDescription = "Předchozí",
                    tint = TextPrimary,
                    modifier = Modifier.size(34.dp)
                )
            }
            FilledIconButton(
                onClick = onTogglePlay,
                modifier = Modifier.size(68.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = AccentMint)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = "Přehrát / Pauza",
                    tint = AmoledBlack,
                    modifier = Modifier.size(38.dp)
                )
            }
            IconButton(onClick = onNext, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.Rounded.SkipNext,
                    contentDescription = "Další",
                    tint = TextPrimary,
                    modifier = Modifier.size(34.dp)
                )
            }
            IconButton(onClick = onCycleRepeat) {
                Icon(
                    imageVector = if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    contentDescription = "Opakovat",
                    tint = if (repeatMode != Player.REPEAT_MODE_OFF) AccentMint else TextSecondary
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
