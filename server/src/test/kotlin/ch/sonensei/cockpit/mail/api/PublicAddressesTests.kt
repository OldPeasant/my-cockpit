package ch.sonensei.cockpit.mail.api

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.net.Inet6Address
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

	/**
	 * A resolver may return IPv4-mapped (`::ffff:a.b.c.d`) or IPv4-compatible (`::a.b.c.d`) addresses as real IPv6
	 * addresses (the JDK's own parser turns them into IPv4 ones); the embedded IPv4 address decides.
	 */
	@ParameterizedTest
	@CsvSource(
		"127.0.0.1, true, false",
		"169.254.169.254, true, false",
		"192.168.1.1, true, false",
		"10.0.0.1, false, false",
		"8.8.8.8, true, true",
		"8.8.8.8, false, true",
	)
	fun `IPv6 addresses embedding an IPv4 address are judged by the embedded one`(
		ipv4: String,
		mapped: Boolean,
		public: Boolean,
	) {
		val bytes = ByteArray(16)
		if (mapped) {
			bytes[10] = 0xff.toByte()
			bytes[11] = 0xff.toByte()
		}
		InetAddress.getByName(ipv4).address.copyInto(bytes, 12)

		PublicAddresses.isPublic(Inet6Address.getByAddress(null, bytes, -1)) shouldBe public
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

	/** Just outside each blocked range: the policy must not over-block ordinary internet hosts. */
	@ParameterizedTest
	@ValueSource(
		strings = [
			"1.0.0.1", "11.0.0.1", "126.255.255.255", "128.0.0.1", "169.253.255.255", "169.255.0.1", "192.0.1.1",
			"192.88.98.1", "198.17.255.255", "198.20.0.1", "198.51.99.1", "203.0.112.1", "223.255.255.254",
			"2001:4860:4860::8888", "2003::1",
		],
	)
	fun `addresses just outside the blocked ranges are public`(address: String) {
		PublicAddresses.isPublic(InetAddress.getByName(address)) shouldBe true
	}

	/**
	 * IPv6 counterparts of ranges the policy documents as blocked: the local-use NAT64 prefix 64:ff9b:1::/48
	 * (RFC 8215, here embedding 10.0.0.1), benchmarking 2001:2::/48 (RFC 5180) and documentation 3fff::/20 (RFC 9637).
	 */
	@ParameterizedTest
	@ValueSource(strings = ["64:ff9b:1::a00:1", "2001:2::1", "3fff::1"])
	fun `IPv6 local-use NAT64, benchmarking and documentation addresses are not public`(address: String) {
		PublicAddresses.isPublic(InetAddress.getByName(address)) shouldBe false
	}

	/** SIIT IPv4-translated addresses (::ffff:0:0:0/96) are blocked whatever IPv4 address they embed. */
	@ParameterizedTest
	@ValueSource(strings = ["::ffff:0:a00:1", "::ffff:0:808:808"])
	fun `IPv6 SIIT IPv4-translated addresses are not public`(address: String) {
		PublicAddresses.isPublic(InetAddress.getByName(address)) shouldBe false
	}
}
