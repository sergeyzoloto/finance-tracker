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
      <button
        data-testid={TEST_ID.loginButton}
        onClick={login}
        className="w-full block bg-slate-400 border-none text-white rounded py-2"
      >
        Let me in!
      </button>
    );
  }

  return (
    <div
      className="flex flex-col w-64 relative my-0 mx-auto gap-2 min-w-fit p-2"
      data-testid={TEST_ID.container}
    >
      {redirect && <Navigate to={'/'} />}
      <form
        data-testid={TEST_ID.form}
        className="flex flex-col w-64 relative my-0 mx-auto gap-2 min-w-fit p-2 box-border"
      >
        <h1>Login</h1>
        <input
          data-testid={TEST_ID.emailInput}
          type="text"
          placeholder="email"
          value={email}
          onChange={(event) => setEmail(event.target.value)}
          className="flexbox border-2 border-solid block w-full p-2 bg-white border-neutral-100 hover:border-neutral-200 text-center rounded max-w-sm"
        />
        <input
          data-testid={TEST_ID.passwordInput}
          type="password"
          placeholder="password"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          className="flexbox border-2 border-solid block w-full p-2 bg-white border-neutral-100 hover:border-neutral-200 text-center rounded max-w-sm"
        />
        {buttonComponent}
      </form>
      {statusComponent}
    </div>
  );
}