package com.sunbeat.sshclient.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sunbeat.sshclient.domain.model.AuthType
import com.sunbeat.sshclient.domain.model.Session

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConnectionCard(
    session: Session,
    isConnected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = session.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    when (session.authType) {
                        AuthType.KEY -> {
                            Spacer(Modifier.width(4.dp))
                            Text("🔑", fontSize = 11.sp)
                        }
                        AuthType.BOTH -> {
                            Spacer(Modifier.width(4.dp))
                            Text("🔑🔒", fontSize = 11.sp)
                        }
                        AuthType.PASSWORD -> {}
                    }
                }
                Text(
                    text = "${session.username}@${session.hostname}:${session.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (session.isFavorite) {
                    Text(
                        text = "★",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (isConnected) {
                Icon(
                    painter = painterResource(id = android.R.drawable.presence_online),
                    contentDescription = "Connected",
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(4.dp))
                Text("ON", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Preview
@Composable
private fun ConnectionCardPreview() {
    MaterialTheme {
        ConnectionCard(
            session = Session(name = "生产服务器", hostname = "prod.example.com", port = 22, username = "root"),
            isConnected = true,
            onClick = {},
        )
    }
}
