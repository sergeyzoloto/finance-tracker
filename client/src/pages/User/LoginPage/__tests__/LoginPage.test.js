import React from 'react';
import { UserContextProvider } from '../../../../context/UserContext';
import LoginPage from '../LoginPage';
import { BrowserRouter as Router } from 'react-router-dom';
import renderer, { act } from 'react-test-renderer';
import { asSlowResponse } from '../../../../__testUtils__/fetchMocks';

import TEST_ID from '../LoginPage.testid';
import { createMemoryHistory } from 'history';

import {
  loginFailedMock,
  loginSuccessMock,
} from '../../../../__testUtils__/fetchUserMocks';

// Reset mocks before every test
beforeEach(() => {
  fetch.resetMocks();
});

describe('LoginPage', () => {
  it('renders login form', () => {
    const component = renderer.create(
      <UserContextProvider>
        <Router>
          <LoginPage />
        </Router>
      </UserContextProvider>,
    );

    // Check if the email input is rendered
    const emailInput = component.root.findByProps({
      'data-testid': TEST_ID.emailInput,
    });
    expect(emailInput).toBeTruthy();

    // Check if the password input is rendered
    const passwordInput = component.root.findByProps({
      'data-testid': TEST_ID.passwordInput,
    });
    expect(passwordInput).toBeTruthy();

    // Check if the login button is rendered
    const loginButton = component.root.findByProps({
      'data-testid': TEST_ID.loginButton,
    });
    expect(loginButton).toBeTruthy();
  });

  it('updates state when input fields change', () => {
    const component = renderer.create(
      <UserContextProvider>
        <Router>
          <LoginPage />
        </Router>
      </UserContextProvider>,
    );

    const testEmail = 'test@mail.com';
    const testPassword = 'testPassword';

    const emailInput = component.root.findByProps({
      'data-testid': TEST_ID.emailInput,
    });
    const passwordInput = component.root.findByProps({
      'data-testid': TEST_ID.passwordInput,
    });

    act(() => {
      emailInput.props.onChange({
        target: { value: testEmail },
      });
      passwordInput.props.onChange({
        target: { value: testPassword },
      });
    });

    expect(emailInput.props.value).toBe(testEmail);
    expect(passwordInput.props.value).toBe(testPassword);
  });

  it('render loading message when login is in progress', async () => {
    const history = createMemoryHistory();
    const testEmail = 'test@mail.com';
    const testPassword = 'testPassword';

    // Mock our fetch with a user
    fetch.mockResponseOnce(asSlowResponse(loginSuccessMock()));

    const component = renderer.create(
      <UserContextProvider>
        <Router>
          <LoginPage />
        </Router>
      </UserContextProvider>,
    );

    const emailInput = component.root.findByProps({
      'data-testid': TEST_ID.emailInput,
    });
    const passwordInput = component.root.findByProps({
      'data-testid': TEST_ID.passwordInput,
    });

    await act(async () => {
      emailInput.props.onChange({
        target: { value: testEmail },
      });
      passwordInput.props.onChange({
        target: { value: testPassword },
      });
      component.root
        .findByProps({ 'data-testid': TEST_ID.loginButton })
        .props.onClick();
    });

    const loadingContainer = component.root.findByProps({
      'data-testid': TEST_ID.loadingContainer,
    });

    expect(loadingContainer).toBeTruthy();

    // Check if the current URL is the home page
    expect(history.location.pathname).toBe('/');
  });

  it('renders error message when login fails', async () => {
    const testEmail = 'test@mail.com';
    const testPassword = 'testPassword';

    // Mock our fetch with a user
    fetch.mockResponseOnce(loginFailedMock());

    const component = renderer.create(
      <UserContextProvider>
        <Router>
          <LoginPage />
        </Router>
      </UserContextProvider>,
    );

    const emailInput = component.root.findByProps({
      'data-testid': TEST_ID.emailInput,
    });
    const passwordInput = component.root.findByProps({
      'data-testid': TEST_ID.passwordInput,
    });

    await act(() => {
      emailInput.props.onChange({
        target: { value: testEmail },
      });
      passwordInput.props.onChange({
        target: { value: testPassword },
      });
      const loginButton = component.root.findByProps({
        'data-testid': TEST_ID.loginButton,
      });

      loginButton.props.onClick();
    });

    // Resolve the fetch promise
    await act(async () => {
      await fetch.mockResolvedValueOnce();
    });

    const errorContainer = component.root.findByProps({
      'data-testid': TEST_ID.errorContainer,
    });

    //console.log(`expect(errorContainer)`, expect(errorContainer));
    expect(errorContainer).toBeDefined();
  });
});
