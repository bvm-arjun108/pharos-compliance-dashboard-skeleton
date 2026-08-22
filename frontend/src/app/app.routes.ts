import { Routes } from '@angular/router';
import { BatchExplorerComponent } from './batch-explorer.component';
import { HomeComponent } from './home.component';
import { PlaceholderViewComponent } from './placeholder-view.component';
import { ReportConfigComponent } from './report-config.component';
import { TransactionReportComponent } from './transaction-report.component';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'batches' },
  {
    path: 'batches/explorer',
    title: 'Batch Explorer | Pharos',
    component: BatchExplorerComponent
  },
  {
    path: 'batches/control-room/:reportGroupId/:batchId/:sequenceNumber',
    title: 'Batch Control Room | Pharos',
    component: PlaceholderViewComponent,
    data: {
      eyebrow: 'Batch investigation',
      title: 'Batch Control Room',
      description: 'The selected batch is ready for a deeper transaction and evidence investigation.',
      items: ['Transformation reconciliation', 'Conditional journey and rule evidence', 'Root-cause transaction analysis']
    }
  },
  { path: 'batches', title: 'Batch View | Pharos', component: HomeComponent },
  {
    path: 'report-config',
    title: 'Report Config | Pharos',
    component: ReportConfigComponent
  },
  {
    path: 'transactions',
    title: 'Transactions | Pharos',
    component: TransactionReportComponent
  },
  {
    path: 'rules',
    title: 'Rules | Pharos',
    component: PlaceholderViewComponent,
    data: {
      eyebrow: 'Rule operations workspace',
      title: 'Rules',
      description: 'Explore rule-hit outcomes, exclusions, and aggregate missed-rule-hit signals.',
      items: ['Rule-hit analysis', 'Exclusion evidence', 'Aggregate missed-rule-hit monitoring']
    }
  },
  { path: '**', redirectTo: 'batches' }
];
