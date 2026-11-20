package io.github.carnager.peek.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.carnager.peek.R
import io.github.carnager.peek.trunk.TrunkAccount
import io.github.carnager.peek.trunk.TrunkClient
import io.github.carnager.peek.ui.bounceClick

/** A person: their face, round, and their name (or an invitation to give one). */
@Composable
fun PersonAvatar(p: TrunkClient.Person, size: Int = 76, onClick: () -> Unit) {
    Column(
        Modifier.width(size.dp + 8.dp).alpha(if (p.hidden) 0.45f else 1f).bounceClick(pressedScale = 0.94f, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AsyncImage(
            model = TrunkAccount.faceUrl(p.cover),
            contentDescription = p.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
        Text(
            p.name.ifEmpty { stringResource(R.string.gallery_person_name_it) },
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (p.name.isEmpty()) FontWeight.Normal else FontWeight.SemiBold,
            color = if (p.name.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text("${p.assetIds.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The row in the trunk tab: the people seen most, not hidden. */
fun LazyListScope.peopleRow(vm: GalleryViewModel) {
    items(vm.trunkPeople.filter { !it.hidden }.take(30), key = { it.id }) { p ->
        PersonAvatar(p) { vm.open = Source.TrunkPerson(p.id) }
    }
}

/**
 * Everybody, hidden ones faded: to open a person, or with [pick] to choose one (merging).
 * [exclude] is left out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleSheet(vm: GalleryViewModel, title: String, exclude: Long? = null, onPick: (Long) -> Unit, onDone: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDone, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.heightIn(max = 560.dp),
        ) {
            items(vm.trunkPeople.filter { it.id != exclude }, key = { it.id }) { p ->
                Box(contentAlignment = Alignment.Center) { PersonAvatar(p) { onPick(p.id) } }
            }
        }
    }
}
