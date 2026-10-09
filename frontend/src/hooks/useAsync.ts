import { useCallback, useEffect, useState } from 'react';

interface AsyncState<T> {
  loading: boolean;
  error?: Error;
  data?: T;
}

/** 화면 진입 시 데이터를 불러오고, 불러오는 중·실패·성공 상태를 돌려준다. deps가 바뀌면 다시 불러온다. */
export function useAsync<T>(fn: () => Promise<T>, deps: unknown[]) {
  const [state, setState] = useState<AsyncState<T>>({ loading: true });

  // eslint-disable-next-line react-hooks/exhaustive-deps
  const load = useCallback(fn, deps);

  const reload = useCallback(() => {
    setState((s) => ({ ...s, loading: true, error: undefined }));
    load()
      .then((data) => setState({ loading: false, data }))
      .catch((error: Error) => setState({ loading: false, error }));
  }, [load]);

  useEffect(() => {
    reload();
  }, [reload]);

  const setData = useCallback((data: T) => setState({ loading: false, data }), []);

  return { ...state, reload, setData };
}
