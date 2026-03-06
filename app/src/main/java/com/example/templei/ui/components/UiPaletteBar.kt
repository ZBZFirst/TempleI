package com.example.templei.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.templei.ui.theme.TempleITheme

@Composable
fun UiPaletteBar(
    items: List<String>,
    onItemClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.forEach { item ->
            AssistChip(
                onClick = { onItemClick(item) },
                label = { Text(text = item) },
                colors = AssistChipDefaults.assistChipColors(
                    labelColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun UiPaletteBarPreview() {
    TempleITheme {
        UiPaletteBar(
            items = listOf("Button", "Text", "Card", "Image"),
            onItemClick = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}
