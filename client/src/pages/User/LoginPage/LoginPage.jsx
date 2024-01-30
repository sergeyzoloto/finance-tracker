import { useEffect, useState } from 'react';
import { Navigate } from 'react-router-dom';
import useFetch from '../../../hooks/useFetch';

import TEST_ID from './LoginPage.testid';

export default function LoginPage() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [redirect, setRedirect] = useState(false);

  const { isLoading, error, performFetch, cancelFetch } = useFetch(
    '/user/login',
    (jsonResult) => {
      if (jsonResult.success) {
        setRedirect(true);
      } else {
        alert('Login failed');
      }
    },
  );

  async function login(event) {
    if (event) {
      event.preventDefault();
    }
    performFetch({
      method: 'POST',
      headers: {
        'content-type': 'application/json',
      },
      body: JSON.stringify({ user: { email, password } }),
    });
  }

  if (redirect) {
    // return <Navigate to={'/'} />;
  }

  useEffect(() => {
    return cancelFetch;
  }, []);

  let statusComponent = null;
  if (error != null) {
    statusComponent = (
      <div data-testid={TEST_ID.errorContainer}>
        Error while trying to login: {error.toString()}
      </div>
    );
  } else if (isLoading) {
    statusComponent = (
      <div data-testid={TEST_ID.loadingContainer}>Logging in...</div>
    );
  }

  let buttonComponent = null;
  if (!isLoading) {
    buttonComponent = (
      <button data-testid={TEST_ID.loginButton} onClick={login}>
        Let me in!
      </button>
    );
  }

  return (
    <div data-testid={TEST_ID.container}>
      <form data-testid={TEST_ID.form}>
        <h1>Login</h1>
        <input
          data-testid={TEST_ID.emailInput}
          type="text"
          placeholder="email"
          value={email}
          onChange={(event) => setEmail(event.target.value)}
        />
        <input
          data-testid={TEST_ID.passwordInput}
          type="password"
          placeholder="password"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
        />
        {buttonComponent}
      </form>
      {statusComponent}
    </div>
  );
}
