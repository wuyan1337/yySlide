package sh.haven.feature.keys

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import sh.haven.core.data.db.entities.StepCaConfig

/**
 * Dialog flow for #133 phase 2: pick a registered CA, name the key,
 * optionally override principals, and submit. Generation itself runs
 * in [KeysViewModel.generateViaStepCa].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GenerateStepCaDialog(
    cas: List<StepCaConfig>,
    onDismiss: () -> Unit,
    onGenerate: (label: String, caId: String, principalsOverride: List<String>) -> Unit,
) {
    require(cas.isNotEmpty()) { "GenerateStepCaDialog launched with no CAs" }
    var label by remember { mutableStateOf("") }
    var selectedCa by remember { mutableStateOf(cas.first()) }
    var principalsOverride by remember { mutableStateOf("") }
    var caMenuExpanded by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.keys_stepca_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.keys_stepca_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                ExposedDropdownMenuBox(
                    expanded = caMenuExpanded,
                    onExpandedChange = { caMenuExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedCa.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.keys_stepca_pick_ca)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = caMenuExpanded)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = caMenuExpanded,
                        onDismissRequest = { caMenuExpanded = false },
                    ) {
                        cas.forEach { ca ->
                            DropdownMenuItem(
                                text = { Text(ca.name) },
                                onClick = {
                                    selectedCa = ca
                                    caMenuExpanded = false
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = principalsOverride,
                    onValueChange = { principalsOverride = it },
                    label = { Text(stringResource(R.string.keys_stepca_principals_override_label)) },
                    singleLine = true,
                    placeholder = {
                        Text(
                            text = selectedCa.defaultPrincipals.ifEmpty { "—" },
                            modifier = Modifier.padding(0.dp),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    keyboard?.hide()
                    val principals = principalsOverride
                        .split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    onGenerate(label.trim(), selectedCa.id, principals)
                },
                enabled = label.isNotBlank(),
            ) {
                Text(stringResource(R.string.keys_stepca_generate))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
