package org.cote.accountmanager.iso42001.certification;

/**
 * Structured result of {@link ISO42001CertificationFactory#verifyCertification} (design §3.1 step 5).
 *
 * <p>{@link #isValid()} is the overall pass/fail and is true only when every individual check passes:
 * the certification {@code status} is {@code VALID}, neither the embedded X.509 certificate nor the
 * application-level certification expiry have lapsed, the report's recomputed content hash still matches
 * the hash that was signed (tamper check), and the signature verifies against the embedded certificate's
 * public key. Each component is also exposed so a caller (or test) can see exactly which check failed.</p>
 */
public class CertificationVerification {

	private boolean statusValid;
	private boolean certificateValid;
	private boolean notExpired;
	private boolean hashMatches;
	private boolean signatureValid;
	private String status;
	private String reason;

	public boolean isValid() {
		return statusValid && certificateValid && notExpired && hashMatches && signatureValid;
	}

	public boolean isStatusValid() {
		return statusValid;
	}

	public void setStatusValid(boolean statusValid) {
		this.statusValid = statusValid;
	}

	public boolean isCertificateValid() {
		return certificateValid;
	}

	public void setCertificateValid(boolean certificateValid) {
		this.certificateValid = certificateValid;
	}

	public boolean isNotExpired() {
		return notExpired;
	}

	public void setNotExpired(boolean notExpired) {
		this.notExpired = notExpired;
	}

	public boolean isHashMatches() {
		return hashMatches;
	}

	public void setHashMatches(boolean hashMatches) {
		this.hashMatches = hashMatches;
	}

	public boolean isSignatureValid() {
		return signatureValid;
	}

	public void setSignatureValid(boolean signatureValid) {
		this.signatureValid = signatureValid;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public String getReason() {
		return reason;
	}

	public void setReason(String reason) {
		this.reason = reason;
	}

	@Override
	public String toString() {
		return "CertificationVerification[valid=" + isValid() + ", status=" + status
			+ ", statusValid=" + statusValid + ", certificateValid=" + certificateValid
			+ ", notExpired=" + notExpired + ", hashMatches=" + hashMatches
			+ ", signatureValid=" + signatureValid + ", reason=" + reason + "]";
	}
}
