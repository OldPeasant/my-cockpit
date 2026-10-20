package ch.sonensei.cockpit.mail.api

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.InetAddress

class PublicAddressesTests {
	@ParameterizedTest
	@ValueSource(
		strings = [
			"0.0.0.0", "0.1.2.3", "10.0.0.1", "100.64.0.1", "100.127.255.255", "127.0.0.1", "127.8.8.8", "169.254.169.254",
			"172.16.0.1", "172.31.255.255", "192.0.0.1", "192.0.2.1", "192.88.99.1", "192.168.1.1", "198.18.0.1", "198.19.255.1",
			"198.51.100.1", "203.0.113.1", "224.0.0.1", "239.1.1.1", "240.0.0.1", "255.255.255.255",
			"::", "::1", "::ffff:127.0.0.1", "::ffff:10.0.0.1", "::10.0.0.1", "fe80::1", "fec0::1", "fc00::1", "fd12:3456::1",
			"ff02::1", "2001:db8::1", "2001::1", "64:ff9b::a00:1", "2002:a00:1::1", "100::1",
		],
	)
	fun `internal and special addresses are not public`(address: String) {
		PublicAddresses.isPublic(InetAddress.getByName(address)) shouldBe false
	}

	@ParameterizedTest
	@ValueSource(
		strings = [
			"1.1.1.1", "8.8.8.8", "93.184.216.34", "100.63.255.255", "100.128.0.1", "172.15.255.255", "172.32.0.1",
			"192.169.0.1", "2a00:1450:4001::1", "2606:4700::1111", "::ffff:8.8.8.8", "64:ff9b::808:808", "2002:808:808::1",
		],
	)
	fun `public internet addresses are public`(address: String) {
		PublicAddresses.isPublic(InetAddress.getByName(address)) shouldBe true
	}
}
