// SPDX-License-Identifier: Apache-2.0
//
// Open-source licenses `/admin/licenses` (docs/15 §9.10, 05 §2.11-10, O-L04, ADR-0010 C-153,
// WO-39): the third-party components of the server, the UI and the agent as the build lists them
// (GET /about/licenses). A part whose list this build does not carry (`available: false`, reason
// `not_generated` or `unreadable`) says so with the next action. Names, versions and licenses come
// from build files: text only (SafeText).
import { useTranslation } from 'react-i18next';

import {
  LICENSE_PARTS,
  useLicenses,
  type LicenseComponent,
  type LicensePart,
  type LicenseSection,
} from '../../../api/admin/about';
import { errorInfo } from '../../../api/errors';
import { DataTable, type DataTableColumn } from '../../../components/DataTable';
import { ErrorState } from '../../../components/ErrorState';
import { LoadingState } from '../../../components/LoadingState';
import { SafeText } from '../../../components/SafeText';

import '../common/admin.css';

function SectionView({
  part,
  section,
}: {
  readonly part: LicensePart;
  readonly section: LicenseSection;
}) {
  const { t } = useTranslation(['common', 'admin']);
  const columns: DataTableColumn<LicenseComponent>[] = [
    {
      id: 'name',
      header: t('admin:licenses.column.name'),
      value: (row) => row.name,
      width: 320,
      render: (row) => <SafeText value={row.name} mono />,
    },
    {
      id: 'version',
      header: t('admin:licenses.column.version'),
      value: (row) => row.version,
      width: 140,
      render: (row) => <SafeText value={row.version} mono />,
    },
    {
      id: 'license',
      header: t('admin:licenses.column.license'),
      value: (row) => row.license,
      width: 220,
      render: (row) => <SafeText value={row.license} />,
    },
  ];
  return (
    <section
      className="admin-panel"
      aria-labelledby={`admin-licenses-${part}-title`}
      data-testid={`admin-licenses-${part}`}
      data-available={section.available ? 'true' : 'false'}
      data-reason={section.reason ?? undefined}
      data-count={String(section.components.length)}
    >
      <h2 id={`admin-licenses-${part}-title`} className="admin-panel__title">
        {t(`admin:licenses.part.${part}`)}
      </h2>
      {section.available ? (
        <>
          <DataTable
            tableId={`admin-licenses-${part}`}
            caption={t(`admin:licenses.part.${part}`)}
            columns={columns}
            rows={section.components}
            rowKey={(row) => `${String(row.name)}@${String(row.version ?? '')}`}
            dataComplete
            empty={{
              title: t('admin:licenses.emptyTitle'),
              description: t('admin:licenses.emptyDescription'),
            }}
          />
          {section.truncated ? (
            <p className="admin-note" data-testid={`admin-licenses-${part}-truncated`}>
              {t('admin:licenses.truncated')}
            </p>
          ) : null}
        </>
      ) : (
        <div data-testid={`admin-licenses-${part}-missing`}>
          <p className="admin-warning">
            {section.reason === 'unreadable'
              ? t('admin:licenses.unreadable')
              : t('admin:licenses.notIncluded')}
          </p>
          <p className="admin-note">{t('admin:licenses.next')}</p>
        </div>
      )}
    </section>
  );
}

export function LicensesPage() {
  const { t } = useTranslation(['common', 'admin']);
  const query = useLicenses();
  const licenses = query.data?.data ?? null;
  const state = query.isPending ? 'loading' : query.isError ? 'error' : 'ready';
  return (
    <section
      className="admin-page"
      aria-labelledby="admin-licenses-title"
      aria-busy={state === 'loading' || undefined}
      data-testid="admin-licenses"
      data-state={state}
    >
      <header className="admin-page__header">
        <h1 id="admin-licenses-title" className="admin-page__title">
          {t('admin:licenses.title')}
        </h1>
      </header>
      <p className="admin-note">{t('admin:licenses.description')}</p>
      {state === 'loading' ? (
        <LoadingState variant="table" data-testid="admin-licenses-loading" />
      ) : state === 'error' || licenses === null ? (
        <ErrorState
          error={errorInfo(query.error)}
          onRetry={() => {
            void query.refetch();
          }}
          data-testid="admin-licenses-error"
        />
      ) : (
        LICENSE_PARTS.map((part) => <SectionView key={part} part={part} section={licenses[part]} />)
      )}
    </section>
  );
}
