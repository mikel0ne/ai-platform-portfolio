import { Routes } from '@angular/router';
import { authGuard } from './core/auth.guard';

export const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('./login/login.component').then(m => m.LoginComponent),
  },
  {
    path: 'chat',
    canActivate: [authGuard],
    loadComponent: () => import('./chat/chat.component').then(m => m.ChatComponent),
  },
  {
    path: 'tickets/:id',
    canActivate: [authGuard],
    loadComponent: () => import('./ticket/ticket-detail.component').then(m => m.TicketDetailComponent),
  },
  {
    path: 'returns/:id',
    canActivate: [authGuard],
    loadComponent: () => import('./returns/return-label.component').then(m => m.ReturnLabelComponent),
  },
  { path: '**', redirectTo: '' },
];
