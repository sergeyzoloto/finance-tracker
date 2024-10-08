import { useState } from "react";

interface FetchOptions extends RequestInit {
  method?: string;
  headers?: Record<string, string>;
}

interface FetchResponse<T = unknown> {
  success: boolean;
  result?: T;
  message?: string;
}

interface UseFetchReturn {
  isLoading: boolean;
  error: string | null;
  performFetch: (options?: FetchOptions) => void;
  cancelFetch: () => void;
}

/**
 * Our useFetch hook should be used for all communication with the server.
 *
 * route - This is the route you want to access on the server. It should NOT include the /api part, so should be /user or /user/{id}
 * onReceived - a function that will be called with the response of the server. Will only be called if everything went well!
 *
 * Our hook will give you an object with the properties:
 *
 * isLoading - true if the fetch is still in progress
 * error - will contain an Error object if something went wrong
 * performFetch - this function will trigger the fetching. It is up to the user of the hook to determine when to do this!
 * cancelFetch - this function will cancel the fetch, call it when your component is unmounted
 */
const useFetch = <T>(
  route: string,
  onReceived: (response: FetchResponse) => void
): UseFetchReturn => {
  /**
   * We use the AbortController which is supported by all modern browsers to handle cancellations
   * For more info: https://developer.mozilla.org/en-US/docs/Web/API/AbortController
   */
  const controller = new AbortController();
  const signal = controller.signal;
  const cancelFetch = () => {
    controller.abort();
  };

  if (route.includes("api/")) {
    /**
     * We add this check here to provide a better error message if you accidentally add the api part
     * As an error that happens later because of this can be very confusing!
     */
    throw Error(
      "when using the useFetch hook, the route should not include the /api/ part"
    );
  }

  const [error, setError] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState<boolean>(false);

  // Add any args given to the function to the fetch function
  const performFetch = (options?: FetchOptions) => {
    setError(null);
    setIsLoading(true);

    const baseOptions: FetchOptions = {
      method: "GET",
      headers: {
        "content-type": "application/json",
      },
    };

    const fetchData = async () => {
      // We add the /api subsection here to make it a single point of change if our configuration changes
      const url = `${process.env.BASE_SERVER_URL}/api${route}`;

      try {
        const res = await fetch(url, { ...baseOptions, ...options, signal });

        if (!res.ok) {
          throw new Error(
            `Fetch for ${url} returned an invalid status (${
              res.status
            }). Received: ${JSON.stringify(res)}`
          );
        }

        const jsonResult: FetchResponse<T> = await res.json();

        if (jsonResult.success) {
          onReceived(jsonResult);
        } else {
          throw new Error(
            jsonResult.message ??
              `The result from our API did not have an error message. Received: ${JSON.stringify(
                jsonResult
              )}`
          );
        }
      } catch (error) {
        if (error instanceof Error) {
          setError(error.message);
        } else {
          setError("An unknown error occurred");
        }
      } finally {
        setIsLoading(false);
      }
    };

    fetchData();
  };

  return { isLoading, error, performFetch, cancelFetch };
};

export default useFetch;
