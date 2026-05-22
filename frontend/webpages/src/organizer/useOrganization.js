import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import api from '../api/client';
import { useLocalStorage } from '../hooks/useLocalStorage';

const OrganizationContext = createContext(null);

const SELECTED_ORG_KEY = 'fairtix.selectedOrgId';

export function OrganizationProvider({ children }) {
  const [orgs, setOrgs] = useState([]);
  const [selectedId, setSelectedId] = useLocalStorage(SELECTED_ORG_KEY, null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState(null);

  const refresh = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      const result = await api.get('/api/organizations/mine');
      setOrgs(result || []);
      if ((result?.length || 0) > 0) {
        const stillValid = result.find((o) => o.id === selectedId);
        if (!stillValid) setSelectedId(result[0].id);
      } else {
        setSelectedId(null);
      }
    } catch (e) {
      setError(e);
      setOrgs([]);
    } finally {
      setIsLoading(false);
    }
  }, [selectedId, setSelectedId]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const current = useMemo(
    () => orgs.find((o) => o.id === selectedId) || null,
    [orgs, selectedId]
  );

  const value = useMemo(
    () => ({
      orgs,
      current,
      selectedId,
      selectOrg: setSelectedId,
      isLoading,
      error,
      refresh,
    }),
    [orgs, current, selectedId, setSelectedId, isLoading, error, refresh]
  );

  return (
    <OrganizationContext.Provider value={value}>{children}</OrganizationContext.Provider>
  );
}

export function useOrganization() {
  const ctx = useContext(OrganizationContext);
  if (!ctx) {
    throw new Error('useOrganization must be used inside OrganizationProvider');
  }
  return ctx;
}
