package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import java.lang.reflect.Proxy;

import org.cote.accountmanager.io.IReader;
import org.cote.accountmanager.io.ISearch;
import org.cote.accountmanager.policy.OperationUtil;
import org.cote.accountmanager.policy.operation.IOperation;
import org.cote.accountmanager.policy.operation.OwnerOperation;
import org.junit.Test;

/// OperationUtil caches one instance per operation class for the life of the JVM, and each instance
/// holds the IReader/ISearch it was built with. Measured 2026-10-26 (TestPbRenderPlanActors.case05 in
/// a multi-test JVM): after BaseTest closed and reopened IOSystem, OwnerOperation still queried the
/// first context's CacheDBSearch - closed, removed from CacheUtil's providers, never invalidated -
/// and reported the pre-move ownerId, so a record re-homed to another user stayed readable by the old
/// owner. The instance must be rebuilt whenever the reader or search differs. Offline: no IOSystem.
public class TestOperationUtil {

	private static final String OWNER = OwnerOperation.class.getName();

	private static ISearch search() {
		return (ISearch) Proxy.newProxyInstance(ISearch.class.getClassLoader(), new Class<?>[] { ISearch.class }, (p, m, a) -> {
			throw new UnsupportedOperationException(m.getName());
		});
	}

	private static IReader reader() {
		return (IReader) Proxy.newProxyInstance(IReader.class.getClassLoader(), new Class<?>[] { IReader.class }, (p, m, a) -> {
			throw new UnsupportedOperationException(m.getName());
		});
	}

	@Test
	public void sameIoReusesTheInstanceDifferentIoRebuildsIt() {
		IReader r1 = reader();
		ISearch s1 = search();
		IOperation first = OperationUtil.getOperationInstance(OWNER, r1, s1);
		assertNotNull(first);
		assertSame("same reader and search: cached", first, OperationUtil.getOperationInstance(OWNER, r1, s1));

		ISearch s2 = search();
		IOperation second = OperationUtil.getOperationInstance(OWNER, r1, s2);
		assertNotNull(second);
		assertNotSame("a different search is a different context: rebuilt", first, second);
		assertSame("and the rebuilt instance is now the cached one", second, OperationUtil.getOperationInstance(OWNER, r1, s2));

		IReader r2 = reader();
		IOperation third = OperationUtil.getOperationInstance(OWNER, r2, s2);
		assertNotSame("a different reader rebuilds too", second, third);
		assertSame(third, OperationUtil.getOperationInstance(OWNER, r2, s2));
	}
}
