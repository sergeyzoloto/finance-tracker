import React, { useEffect, useState } from 'react';
import { Navigate } from 'react-router-dom';

import CredentialsInput from '../../../components/CredentialsInput/CredentialsInput';
import useFetch from '../../../hooks/useFetch';
import TEST_ID from './CreateUser.testid';

/* Styles */
const styles = {
  CONTAINER: 'flex flex-col w-64 relative my-0 mx-auto gap-2 min-w-fit p-2',
  FORM: 'flex flex-col w-64 relative my-0 mx-auto gap-2 min-w-fit p-2 box-border',
  SUBMIT_BUTTON:
    'w-full block bg-slate-400 border-none text-white rounded py-2',
};

const CreateUser = () => {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [redirect, setRedirect] = useState(false);

  const onSuccess = () => {
    setPassword('');
    setEmail('');
    setRedirect(true);
  };

  const { isLoading, error, performFetch, cancelFetch } = useFetch(
    '/user/register',
    onSuccess,
  );

  useEffect(() => {
    return cancelFetch;
  }, []);

  const handleSubmit = (event) => {
    event.preventDefault();
    performFetch({
      method: 'POST',
      headers: {
        'content-type': 'application/json',
      },
      body: JSON.stringify({ user: { email, password } }),
    });
  };

  let statusComponent = null;
  if (error != null) {
    statusComponent = (
      <div data-testid={TEST_ID.errorContainer}>
        Error while trying to create user: {error.toString()}
      </div>
    );
  } else if (isLoading) {
    statusComponent = (
      <div data-testid={TEST_ID.loadingContainer}>Creating user....</div>
    );
  }

  const handleEmailChange = (event) => {
    setEmail(event.target.value);
  };

  const handlePasswordChange = (event) => {
    setPassword(event.target.value);
  };

  return (
    <div data-testid={TEST_ID.container} className={styles.CONTAINER}>
      {redirect && <Navigate to={'/user/list'} />}
      <form onSubmit={handleSubmit} className={styles.FORM}>
        <h1>What should the user be?</h1>
        <CredentialsInput
          name="email"
          placeholder="email"
          value={email}
          onChange={handleEmailChange}
          data-testid={TEST_ID.emailInput}
        />
        <CredentialsInput
          name="password"
          placeholder="password"
          value={password}
          onChange={handlePasswordChange}
          data-testid={TEST_ID.passwordInput}
        />
        <button
          type="submit"
          data-testid={TEST_ID.submitButton}
          className={styles.SUBMIT_BUTTON}
        >
          Submit
        </button>
      </form>
      {statusComponent}
    </div>
  );
};

export default CreateUser;
