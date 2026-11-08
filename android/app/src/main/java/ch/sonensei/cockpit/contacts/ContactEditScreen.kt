package ch.sonensei.cockpit.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ch.sonensei.cockpit.R

internal object ContactEditTags {
    const val FORM = "contact-form"
    const val SAVE = "contact-form-save"
    const val CANCEL = "contact-form-cancel"
    const val PROBLEM = "contact-form-problem"
    const val ERROR = "contact-form-error"
    const val GIVEN = "contact-form-given"
    const val FAMILY = "contact-form-family"
    const val DISPLAY = "contact-form-display"
    const val ORGANIZATION = "contact-form-organization"
    const val TITLE = "contact-form-title"
    const val BIRTHDAY = "contact-form-birthday"
    const val NOTES = "contact-form-notes"
    const val ADD_EMAIL = "contact-form-add-email"
    const val ADD_PHONE = "contact-form-add-phone"
    const val ADD_ADDRESS = "contact-form-add-address"

    fun email(index: Int) = "contact-form-email:$index"

    fun phone(index: Int) = "contact-form-phone:$index"

    fun street(index: Int) = "contact-form-street:$index"

    fun city(index: Int) = "contact-form-city:$index"

    fun remove(
        kind: String,
        index: Int,
    ) = "contact-form-remove-$kind:$index"
}

/** What the form can ask for, so it can be tested without a view model. */
data class ContactEditCallbacks(
    val change: (ContactForm) -> Unit = {},
    val save: () -> Unit = {},
    val cancel: () -> Unit = {},
)

/** The create/edit form: names, organization, repeatable emails, phones and addresses, birthday and notes. */
@Composable
fun ContactEditContent(
    state: ContactEditUiState,
    callbacks: ContactEditCallbacks,
    modifier: Modifier = Modifier,
) {
    val form = state.form
    val change = callbacks.change
    Column(modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = callbacks.cancel, enabled = !state.saving, modifier = Modifier.testTag(ContactEditTags.CANCEL)) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.contacts_cancel))
            }
            Text(
                stringResource(if (state.base == null) R.string.contacts_new else R.string.contacts_edit_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            Button(onClick = callbacks.save, enabled = !state.saving, modifier = Modifier.testTag(ContactEditTags.SAVE)) {
                Text(stringResource(R.string.contacts_save))
            }
        }
        if (state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { ErrorBanner(saveErrorText(it, state.base, state.errorDetail), ContactEditTags.ERROR) }
        state.problem?.let {
            ErrorBanner(
                stringResource(
                    when (it) {
                        ContactFormProblem.NAME_REQUIRED -> R.string.contacts_name_required
                        ContactFormProblem.INVALID_BIRTHDAY -> R.string.contacts_invalid_birthday
                    },
                ),
                ContactEditTags.PROBLEM,
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .testTag(ContactEditTags.FORM),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Field(form.givenName, R.string.contacts_given_name, ContactEditTags.GIVEN) { change(form.copy(givenName = it)) }
            Field(form.familyName, R.string.contacts_family_name, ContactEditTags.FAMILY) { change(form.copy(familyName = it)) }
            Field(form.displayName, R.string.contacts_display_name, ContactEditTags.DISPLAY) { change(form.copy(displayName = it)) }
            Field(form.organization, R.string.contacts_organization, ContactEditTags.ORGANIZATION) {
                change(form.copy(organization = it))
            }
            Field(form.title, R.string.contacts_job_title, ContactEditTags.TITLE) { change(form.copy(title = it)) }

            SectionTitle(R.string.contacts_emails)
            form.emails.forEachIndexed { i, email ->
                LabeledRow(
                    email,
                    R.string.contacts_emails,
                    KeyboardType.Email,
                    ContactEditTags.email(i),
                    ContactEditTags.remove("email", i),
                    onChange = { change(form.copy(emails = form.emails.replace(i, it))) },
                    onRemove = { change(form.copy(emails = form.emails.without(i))) },
                )
            }
            AddButton(R.string.contacts_add_email, ContactEditTags.ADD_EMAIL) { change(form.copy(emails = form.emails + LabeledField())) }

            SectionTitle(R.string.contacts_phones)
            form.phones.forEachIndexed { i, phone ->
                LabeledRow(
                    phone,
                    R.string.contacts_phones,
                    KeyboardType.Phone,
                    ContactEditTags.phone(i),
                    ContactEditTags.remove("phone", i),
                    onChange = { change(form.copy(phones = form.phones.replace(i, it))) },
                    onRemove = { change(form.copy(phones = form.phones.without(i))) },
                )
            }
            AddButton(R.string.contacts_add_phone, ContactEditTags.ADD_PHONE) { change(form.copy(phones = form.phones + LabeledField())) }

            SectionTitle(R.string.contacts_addresses)
            form.addresses.forEachIndexed { i, address ->
                AddressBlock(i, address, onChange = { change(form.copy(addresses = form.addresses.replace(i, it))) }) {
                    change(form.copy(addresses = form.addresses.without(i)))
                }
            }
            AddButton(R.string.contacts_add_address, ContactEditTags.ADD_ADDRESS) {
                change(form.copy(addresses = form.addresses + AddressField()))
            }

            SectionTitle(R.string.contacts_birthday)
            OutlinedTextField(
                value = form.birthday,
                onValueChange = { change(form.copy(birthday = it)) },
                placeholder = { Text(stringResource(R.string.contacts_birthday_hint)) },
                singleLine = true,
                isError = state.problem == ContactFormProblem.INVALID_BIRTHDAY,
                modifier = Modifier.fillMaxWidth().testTag(ContactEditTags.BIRTHDAY),
            )
            SectionTitle(R.string.contacts_notes)
            OutlinedTextField(
                value = form.notes,
                onValueChange = { change(form.copy(notes = it)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth().testTag(ContactEditTags.NOTES),
            )
        }
    }
}

@Composable
internal fun saveErrorText(
    error: ContactSaveError,
    base: Contact?,
    detail: String? = null,
): String =
    when (error) {
        ContactSaveError.OFFLINE -> stringResource(R.string.contacts_save_offline)
        ContactSaveError.FAILED -> stringResource(R.string.contacts_save_failed)
        ContactSaveError.INVALID ->
            detail?.let { stringResource(R.string.contacts_save_invalid_detail, it) }
                ?: stringResource(R.string.contacts_save_invalid)
        ContactSaveError.CONFLICT -> stringResource(R.string.contacts_save_conflict)
        ContactSaveError.NOT_ALLOWED -> stringResource(R.string.contacts_not_allowed, sourceName(base?.source ?: "LOCAL"))
    }

/** `GOOGLE` → `Google`, like the web's `sourceLabel`. */
internal fun sourceName(source: String): String =
    when (source) {
        "GOOGLE" -> "Google"
        "MICROSOFT" -> "Microsoft"
        "CARDDAV" -> "CardDAV"
        else -> typeName(source)
    }

@Composable
internal fun ErrorBanner(
    text: String,
    tag: String,
) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
    }
}

@Composable
private fun Field(
    value: String,
    label: Int,
    tag: String,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

@Composable
private fun SectionTitle(title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun LabeledRow(
    field: LabeledField,
    label: Int,
    keyboard: KeyboardType,
    tag: String,
    removeTag: String,
    onChange: (LabeledField) -> Unit,
    onRemove: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = field.value,
            onValueChange = { onChange(field.copy(value = it)) },
            label = { Text(stringResource(label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            modifier = Modifier.weight(2f).testTag(tag),
        )
        OutlinedTextField(
            value = field.label,
            onValueChange = { onChange(field.copy(label = it)) },
            label = { Text(stringResource(R.string.contacts_label)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove, modifier = Modifier.testTag(removeTag)) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.contacts_remove_field))
        }
    }
}

@Composable
private fun AddressBlock(
    index: Int,
    address: AddressField,
    onChange: (AddressField) -> Unit,
    onRemove: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = address.street,
                onValueChange = { onChange(address.copy(street = it)) },
                label = { Text(stringResource(R.string.contacts_street)) },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag(ContactEditTags.street(index)),
            )
            IconButton(onClick = onRemove, modifier = Modifier.testTag(ContactEditTags.remove("address", index))) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.contacts_remove_field))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = address.postalCode,
                onValueChange = { onChange(address.copy(postalCode = it)) },
                label = { Text(stringResource(R.string.contacts_postal_code)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = address.city,
                onValueChange = { onChange(address.copy(city = it)) },
                label = { Text(stringResource(R.string.contacts_city)) },
                singleLine = true,
                modifier = Modifier.weight(2f).testTag(ContactEditTags.city(index)),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = address.region,
                onValueChange = { onChange(address.copy(region = it)) },
                label = { Text(stringResource(R.string.contacts_region)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = address.country,
                onValueChange = { onChange(address.copy(country = it)) },
                label = { Text(stringResource(R.string.contacts_country)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = address.label,
            onValueChange = { onChange(address.copy(label = it)) },
            label = { Text(stringResource(R.string.contacts_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AddButton(
    text: Int,
    tag: String,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, modifier = Modifier.testTag(tag)) {
        Icon(Icons.Outlined.Add, contentDescription = null)
        Text(stringResource(text), modifier = Modifier.padding(start = 8.dp))
    }
}

private fun <T> List<T>.replace(
    index: Int,
    value: T,
) = mapIndexed { i, old -> if (i == index) value else old }

private fun <T> List<T>.without(index: Int) = filterIndexed { i, _ -> i != index }
