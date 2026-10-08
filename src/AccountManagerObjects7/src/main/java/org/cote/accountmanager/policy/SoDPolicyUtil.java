/*******************************************************************************
 * Copyright (C) 2002, 2020 Stephen Cote Enterprises, LLC. All rights reserved.
 * Redistribution without modification is permitted provided the following conditions are met:
 *
 *    1. Redistribution may not deviate from the original distribution,
 *        and must reproduce the above copyright notice, this list of conditions
 *        and the following disclaimer in the documentation and/or other materials
 *        provided with the distribution.
 *    2. Products may be derived from this software.
 *    3. Redistributions of any form whatsoever must retain the following acknowledgment:
 *        "This product includes software developed by Stephen Cote Enterprises, LLC"
 *
 * THIS SOFTWARE IS PROVIDED BY STEPHEN COTE ENTERPRISES, LLC ``AS IS''
 * AND ANY EXPRESSED OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
 * THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THIS PROJECT OR ITS CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *******************************************************************************/
package org.cote.accountmanager.policy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.EffectEnumType;

/**
 * Separation-of-duty support for PolicyEvaluator.evaluateSoD (PatternEnumType.SEPARATION_OF_DUTY).
 *
 * An "activity" is an auth.group. The permissions that make up the activity are whatever has been
 * granted on that group - participations on the group with effectType GRANT_PERMISSION, to any
 * participant (user, account, person or role). A reference (account or person) "holds" one of those
 * permissions when AuthorizationUtil.checkEntitlement says so: a direct grant, a grant to a role the
 * reference is in (hierarchy-aware), or - for a person - a grant to one of its accounts.
 *
 * ALL vs ANY is explicit. The AM6 port of this class carried a note that the check was "just ANY"
 * while its body cleared the result unless every permission matched, so callers could not tell which
 * they were getting; the AM7 methods are named for what they test and PolicyEvaluator.evaluateSoD
 * selects by the pattern's comparator (ANY => any, otherwise all).
 *
 * Both predicates are fail-closed: an activity with no granted permissions, an unresolvable group or a
 * null reference is never a match.
 */
public class SoDPolicyUtil {
	public static final Logger logger = LogManager.getLogger(SoDPolicyUtil.class);

	private SoDPolicyUtil() {
		/// static utility
	}

	/// Resolve the activity group by urn. Returns null when it does not exist.
	public static BaseRecord getActivity(String activityUrn) {
		if(activityUrn == null) {
			return null;
		}
		try {
			return IOSystem.getActiveContext().getReader().readByUrn(ModelNames.MODEL_GROUP, activityUrn);
		} catch (ReaderException e) {
			logger.error(e);
		}
		return null;
	}

	/// The distinct permission ids granted on the activity to anyone, in first-seen order.
	public static List<Long> getActivityPermissions(String activityUrn){
		return getActivityPermissions(getActivity(activityUrn));
	}

	public static List<Long> getActivityPermissions(BaseRecord activity){
		List<Long> perms = new ArrayList<>();
		if(activity == null || !activity.inherits(ModelNames.MODEL_GROUP)) {
			return perms;
		}
		Query q = QueryUtil.createParticipationQuery(null, activity, null, null, null);
		q.field(FieldNames.FIELD_EFFECT_TYPE, EffectEnumType.GRANT_PERMISSION);
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_PERMISSION_ID});
		/// Grants may have just been written by the caller; do not serve a stale participation page.
		q.setCache(false);
		try {
			QueryResult qr = IOSystem.getActiveContext().getSearch().find(q);
			Set<Long> seen = new LinkedHashSet<>();
			if(qr != null) {
				for(BaseRecord part : qr.getResults()) {
					long pid = part.get(FieldNames.FIELD_PERMISSION_ID);
					if(pid > 0L) {
						seen.add(pid);
					}
				}
			}
			perms.addAll(seen);
		} catch (ReaderException e) {
			logger.error(e);
		}
		return perms;
	}

	/// The subset of the activity's permissions that reference effectively holds on the activity.
	/// This is data, not a verdict - use hasAnyActivityPermission / hasAllActivityPermissions for the SoD decision.
	public static List<Long> getActivityPermissionsForType(String activityUrn, BaseRecord reference){
		return getActivityPermissionsForType(getActivity(activityUrn), reference);
	}

	public static List<Long> getActivityPermissionsForType(BaseRecord activity, BaseRecord reference){
		List<Long> held = new ArrayList<>();
		if(activity == null || reference == null) {
			return held;
		}
		List<Long> actPerms = getActivityPermissions(activity);
		if(actPerms.isEmpty()){
			logger.warn("Zero permissions found for activity " + activity.get(FieldNames.FIELD_URN));
			return held;
		}
		for(long pid : actPerms) {
			BaseRecord perm = null;
			try {
				perm = IOSystem.getActiveContext().getReader().read(ModelNames.MODEL_PERMISSION, pid);
			} catch (ReaderException e) {
				logger.error(e);
			}
			if(perm == null) {
				logger.warn("Permission #" + pid + " granted on activity " + activity.get(FieldNames.FIELD_URN) + " could not be read");
				continue;
			}
			if(IOSystem.getActiveContext().getAuthorizationUtil().checkEntitlement(reference, perm, activity)) {
				held.add(pid);
			}
		}
		return held;
	}

	/// ANY: the reference holds at least one of the activity's permissions.
	public static boolean hasAnyActivityPermission(BaseRecord activity, BaseRecord reference) {
		return !getActivityPermissionsForType(activity, reference).isEmpty();
	}

	public static boolean hasAnyActivityPermission(String activityUrn, BaseRecord reference) {
		return hasAnyActivityPermission(getActivity(activityUrn), reference);
	}

	/// ALL: the reference holds every one of the activity's permissions. False when the activity defines none.
	public static boolean hasAllActivityPermissions(BaseRecord activity, BaseRecord reference) {
		List<Long> actPerms = getActivityPermissions(activity);
		if(actPerms.isEmpty()) {
			return false;
		}
		List<Long> held = getActivityPermissionsForType(activity, reference);
		if(held.size() != actPerms.size()) {
			logger.info("Reference holds " + held.size() + " of " + actPerms.size() + " permissions for activity " + activity.get(FieldNames.FIELD_URN) + " - ALL not satisfied");
			return false;
		}
		return held.containsAll(actPerms);
	}

	public static boolean hasAllActivityPermissions(String activityUrn, BaseRecord reference) {
		return hasAllActivityPermissions(getActivity(activityUrn), reference);
	}
}
