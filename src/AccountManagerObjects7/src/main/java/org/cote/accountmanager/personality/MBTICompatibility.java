package org.cote.accountmanager.personality;

public class MBTICompatibility {
	private String key1 = null;
	private String key2 = null;
	private CompatibilityEnumType compatibility = CompatibilityEnumType.UNKNOWN;
	public MBTICompatibility(String key1, String key2, CompatibilityEnumType compatibility) {
		this.key1 = key1;
		this.key2 = key2;
		this.compatibility = compatibility;
	}
	public String getKey1() {
		return key1;
	}
	public String getKey2() {
		return key2;
	}
	public CompatibilityEnumType getCompatibility() {
		return compatibility;
	}
	
}
