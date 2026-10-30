"""Which of this computer's IPs the banner shows and mDNS advertises (phones must reach it)."""

from sensorstream.app import order_ips


def test_ubuntu_hostname_loopback_is_dropped():
    # Ubuntu maps the hostname to 127.0.1.1 in /etc/hosts, so that is all getaddrinfo returns.
    assert order_ips("192.168.31.216", ["127.0.1.1"]) == ["192.168.31.216"]


def test_default_route_interface_comes_first_even_with_docker():
    # sorted() alone would put the Docker bridge first and mDNS would advertise it.
    assert order_ips("192.168.31.216", ["172.17.0.1", "192.168.31.216"])[0] == "192.168.31.216"


def test_link_local_and_missing_route():
    assert order_ips(None, ["169.254.3.4", "10.0.0.5"]) == ["10.0.0.5"]
    assert order_ips(None, ["127.0.0.1"]) == []
