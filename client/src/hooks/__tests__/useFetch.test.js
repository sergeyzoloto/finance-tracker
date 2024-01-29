import { create, act } from 'react-test-renderer';

import useFetch from '../useFetch';
import {
  getUsersSuccessMock,
  getUsersFailedMock,
} from '../../__testUtils__/fetchUserMocks';

// Reset mocks before every test
beforeEach(() => {
  fetch.resetMocks();
});

describe('useFetch', () => {
  it('Fetch process works as expected', async () => {
    fetch.mockResponseOnce(getUsersSuccessMock());
    const mockSuccessFn = jest.fn(() => {});

    let testProps;
    const TestComponent = () => {
      testProps = useFetch('/', mockSuccessFn);
      return null;
    };

    let component;
    act(() => {
      component = create(<TestComponent />);
    });

    // Nothing is performed yet
    expect(fetch.mock.calls.length).toEqual(0);
    expect(testProps.isLoading).toBe(false);
    expect(mockSuccessFn).not.toHaveBeenCalled();

    // Call performFetch
    act(() => {
      testProps.performFetch();
    });

    // Should be loading now
    expect(testProps.isLoading).toBe(true);

    // Resolve the fetch promise
    await act(async () => {
      await fetch.mockResolvedValueOnce();
    });

    // Should not be loading anymore
    expect(testProps.isLoading).toBe(false);

    // Fetch should have been called and our success function should have been called
    expect(fetch.mock.calls.length).toEqual(1);
    expect(mockSuccessFn).toHaveBeenCalled();
  });

  it('Should set the error if something goes wrong on the server', async () => {
    fetch.mockResponseOnce(getUsersFailedMock());
    const mockErrorFn = jest.fn(() => {});

    let testProps;
    const TestComponent = () => {
      testProps = useFetch('/', mockErrorFn);
      return null;
    };

    let component;
    act(() => {
      component = create(<TestComponent />);
    });

    // Nothing is performed yet
    expect(testProps.isLoading).toBe(false);
    expect(testProps.error).toBe(null);

    // Call performFetch
    act(() => {
      testProps.performFetch();
    });

    // Should be loading now
    expect(testProps.isLoading).toBe(true);

    // Resolve the fetch promise
    await act(async () => {
      await fetch.mockResolvedValueOnce();
    });

    // Should not be loading anymore
    expect(testProps.isLoading).toBe(false);

    // Should have an error
    expect(testProps.error).not.toBe(null);
  });
});
