package ch.sonensei.cockpit.contacts.aggregate

import ch.sonensei.cockpit.contacts.api.ContactBadRequestResponse
import ch.sonensei.cockpit.contacts.api.ContactNotFoundResponse
import ch.sonensei.cockpit.core.web.InvalidRequestException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Contact detail aggregation and autocomplete (SPEC.md §8.3). */
@RestController
@RequestMapping("/api/v1/contacts")
@Tag(name = "Contacts")
class ContactAggregateController(
	private val aggregate: ContactAggregateService,
) {
	@GetMapping("/{id}/related", produces = [MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE])
	@Operation(
		operationId = "getContactRelated",
		summary = "Mails, upcoming events and Waiting For items of a contact",
		description =
			"Mails: the newest 20 located messages (not in Trash, Spam or Drafts) with one of the contact's email " +
				"addresses in From, To or Cc. Events: the next 20 occurrences within 90 days (visible calendars, not " +
				"cancelled) with one of its addresses as attendee. Waiting For: open tasks on that list naming the contact.",
	)
	@ApiResponse(responseCode = "200", description = "The related items")
	@ContactNotFoundResponse
	fun related(
		@PathVariable id: UUID,
	): ContactRelatedResponse = ContactRelatedResponse.of(aggregate.related(id))

	@GetMapping("/autocomplete", produces = [MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE])
	@Operation(
		operationId = "autocompleteContacts",
		summary = "Suggest contacts and recent correspondents",
		description =
			"Contacts whose display name or an email address contains `q` (one entry per address; names starting with " +
				"`q` first, each group by name), then addresses from recent mails whose address or name contains `q` and " +
				"that are not an address of a suggested contact or of the owner, most recently seen first. Such an address " +
				"that belongs to a contact carries its `contactId` and display name. A blank `q` has no suggestions.",
	)
	@ApiResponse(responseCode = "200", description = "The suggestions")
	@ContactBadRequestResponse
	fun autocomplete(
		@Parameter(description = "The text typed so far") @RequestParam q: String,
		@Parameter(description = "Maximum number of suggestions, 1-50 (default 10)") @RequestParam(required = false) limit: Int?,
	): List<ContactSuggestionDto> {
		val max = limit ?: ContactAggregateService.DEFAULT_AUTOCOMPLETE_LIMIT
		if (max !in 1..ContactAggregateService.MAX_AUTOCOMPLETE_LIMIT) {
			throw InvalidRequestException("limit", "must be between 1 and ${ContactAggregateService.MAX_AUTOCOMPLETE_LIMIT}")
		}
		return aggregate.autocomplete(q, max).map(ContactSuggestionDto::of)
	}
}
