// 管理员面板状态：聚合 open/reports/filter/page/totalPages/counts
import { useReducer } from 'react';
import type { ReportView, ReportFilter } from '../types';

type State = {
  open: boolean;
  reports: ReportView[];
  filter: ReportFilter;
  page: number;
  totalPages: number;
  counts: { total: number; pending: number };
};

type Action =
  | { kind: 'open' }
  | { kind: 'close' }
  | { kind: 'setReports'; reports: ReportView[]; totalPages: number }
  | { kind: 'setFilter'; filter: ReportFilter }
  | { kind: 'setPage'; page: number }
  | { kind: 'setCounts'; counts: { total: number; pending: number } }
  | { kind: 'handleReport'; id: number; status: 'RESOLVED' | 'DISMISSED'; handler: string };

const initial: State = {
  open: false,
  reports: [],
  filter: 'PENDING',
  page: 0,
  totalPages: 0,
  counts: { total: 0, pending: 0 },
};

function reducer(state: State, action: Action): State {
  switch (action.kind) {
    case 'open': return { ...state, open: true, filter: 'PENDING', page: 0 };
    case 'close': return { ...state, open: false };
    case 'setReports': return { ...state, reports: action.reports, totalPages: action.totalPages };
    case 'setFilter': return { ...state, filter: action.filter, page: 0 };
    case 'setPage': return { ...state, page: action.page };
    case 'setCounts': return { ...state, counts: action.counts };
    case 'handleReport':
      return {
        ...state,
        reports: state.reports.map(r => r.id === action.id ? { ...r, status: action.status, handledBy: action.handler } : r),
        counts: { ...state.counts, pending: Math.max(0, state.counts.pending - 1) },
      };
  }
}

export function useAdminState() {
  const [state, dispatch] = useReducer(reducer, initial);
  return {
    state,
    open: () => dispatch({ kind: 'open' }),
    close: () => dispatch({ kind: 'close' }),
    setReports: (reports: ReportView[], totalPages: number) => dispatch({ kind: 'setReports', reports, totalPages }),
    setFilter: (filter: ReportFilter) => dispatch({ kind: 'setFilter', filter }),
    setPage: (page: number) => dispatch({ kind: 'setPage', page }),
    setCounts: (counts: { total: number; pending: number }) => dispatch({ kind: 'setCounts', counts }),
    handleReport: (id: number, status: 'RESOLVED' | 'DISMISSED', handler: string) => dispatch({ kind: 'handleReport', id, status, handler }),
  };
}
