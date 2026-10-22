package ch.sonensei.cockpit.mail.compose

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import org.springframework.context.annotation.Configuration
import org.springframework.util.unit.DataSize

/** Limits of composing mail (`cockpit.mail.compose.*`). The defaults suit production. */
@ConfigurationProperties("cockpit.mail.compose")
class ComposeProperties(
	/** Largest draft or outgoing message (body and decoded attachments), like the limit of most mail providers. */
	@DefaultValue("25MB") val maxMessageSize: DataSize,
) {
	init {
		require(maxMessageSize.toBytes() in 1..Int.MAX_VALUE) { "cockpit.mail.compose.max-message-size must be between 1 byte and 2 GB" }
	}
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ComposeProperties::class)
class ComposeConfiguration
