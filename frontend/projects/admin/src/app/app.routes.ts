import { Routes } from '@angular/router';
import { adminGuard } from 'auth';
import {
  AdminDashboardPage,
  AdminLoginPage,
  AdminShell,
  AiUsagePage,
  SettingsPage,
  UsersPage,
} from './pages';
export const routes: Routes = [
  { path: 'login', component: AdminLoginPage },
  {
    path: '',
    component: AdminShell,
    canActivate: [adminGuard],
    children: [
      { path: 'dashboard', component: AdminDashboardPage },
      { path: 'usuarios', component: UsersPage },
      { path: 'consumo-ia', component: AiUsagePage },
      { path: 'configuracoes', component: SettingsPage },
      { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
    ],
  },
  { path: '**', redirectTo: 'dashboard' },
];
