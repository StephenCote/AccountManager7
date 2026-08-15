package org.cote.accountmanager.olio.sd;

public enum SDAPIEnumType {
	UNKNOWN,
	AUTO1111,
	SWARM,
	/// Declared so olio.pb.artifact.backend can validate against it.  There is NO ComfyUI behaviour
	/// behind this value yet - the backend itself is backlogged and SwarmUI remains the only one
	/// implemented.
	COMFY
}
