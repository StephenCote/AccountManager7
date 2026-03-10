package org.cote.accountmanager.policy.operation;

import org.cote.accountmanager.io.IReader;
import org.cote.accountmanager.io.ISearch;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.type.OperationResponseEnumType;

/**
 * DelegateApprovalOperation extends AccessApprovalOperation to check for approval
 * from a delegate approver instead of the direct approver.
 *
 * The delegate target is resolved from the referenceFact (matchFact) the same way
 * as the direct approver, but the policy structure places this operation in a separate
 * pattern to allow the delegate to act independently.
 *
 * The policy structure uses condition=ANY on the rule so that either the direct approver
 * (AccessApprovalOperation) OR the delegate (DelegateApprovalOperation) can satisfy
 * the approval requirement.
 *
 * This class reuses all of AccessApprovalOperation's logic — the only distinction is
 * semantic: the policy author places this operation on delegate-targeted patterns.
 */
public class DelegateApprovalOperation extends AccessApprovalOperation {

	public DelegateApprovalOperation(IReader reader, ISearch search) {
		super(reader, search);
	}

	@Override
	public OperationResponseEnumType operate(BaseRecord prt, BaseRecord prr, BaseRecord pattern, BaseRecord sourceFact, BaseRecord referenceFact) {
		/// Delegate uses the same approval check/notification logic.
		/// The referenceFact points to the delegate user instead of the direct approver.
		return super.operate(prt, prr, pattern, sourceFact, referenceFact);
	}
}
