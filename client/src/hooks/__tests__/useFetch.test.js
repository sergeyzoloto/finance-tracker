import { renderHook, act } from '@testing-library/react';

import useFetch from '../useFetch';
import {
  getUsersSuccessMock,
  getUsersFailedMock,
} from '../../__testUtils__/fetchUserMocks';

// Reset mocks before every test
beforeEach(() => {
  fetch.resetMocks();
});

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

// simple analogue of waitForNextUpdate
async function waitFor(callback, timeout = 500) {
  const step = 10;
  let timeSpent = 0;
  let timedOut = false;

  while (true) {
    try {
      await sleep(step);
      timeSpent += step;
      callback();
      break;
    } catch {}
    if (timeSpent >= timeout) {
      timedOut = true;
      break;
    }
  }

  if (timedOut) {
    throw new Error("time's out");
  }
}

describe('useFetch', () => {
  it('Fetch process works as expected', async () => {
    fetch.mockResponseOnce(getUsersSuccessMock());
    const mockSuccessFn = jest.fn(() => {});

    const { result } = renderHook(() => useFetch('/', mockSuccessFn));

    // Nothing is performed yet
    expect(fetch.mock.calls.length).toEqual(0);
    expect(result.current.isLoading).toBe(false);
    expect(mockSuccessFn).not.toHaveBeenCalled();

    act(() => {
      result.current.performFetch();
    });

    // Should be loading
    expect(result.current.isLoading).toBe(true);

    await waitFor(() => {
      // Should not be loading anymore
      expect(result.current.isLoading).toBe(false);

      // Fetch should have been called and our success function should have been called
      expect(fetch.mock.calls.length).toEqual(1);
      expect(mockSuccessFn).toHaveBeenCalled();
    });
  });

  it('Should set the error if something goes wrong on the server', async () => {
    fetch.mockResponseOnce(getUsersFailedMock());

    const { result } = renderHook(() => useFetch('/', () => {}));

    act(() => {
      result.current.performFetch();
    });

    // Should be loading
    expect(result.current.isLoading).toBe(true);
    expect(result.current.error).toBe(null);

    await waitFor(() => {
      // Should not be loading anymore
      expect(result.current.isLoading).toBe(false);

      // Should have an error
      expect(result.current.error).not.toBe(null);
    });
  });
});
