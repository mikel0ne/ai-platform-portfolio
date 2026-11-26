import { Component, inject, signal, ChangeDetectionStrategy } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { ChatApiService } from '../core/chat-api.service';
import { UserDto } from '../core/models';

@Component({
  selector: 'app-login',
  imports: [],
  templateUrl: './login.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './login.component.css',
})
export class LoginComponent {
  private readonly api = inject(ChatApiService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  readonly users = signal<UserDto[]>([]);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly loginError = signal<string | null>(null);
  readonly brokenAvatars = new Set<string>();

  constructor() {
    this.api.users().subscribe({
      next: u => {
        this.users.set(u);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('No se pudieron cargar los usuarios. ¿Está el backend arrancado?');
        this.loading.set(false);
      },
    });
  }

  enter(user: UserDto): void {
    this.loginError.set(null);
    this.api.login(user.id).subscribe({
      next: ({ token }) => {
        this.auth.login(user, token);
        this.router.navigateByUrl('/chat');
      },
      error: () => this.loginError.set('No se pudo iniciar sesión. Inténtalo de nuevo.'),
    });
  }
}
