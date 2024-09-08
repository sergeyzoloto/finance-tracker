// Define the shape of the user state
export interface UserState {
  isAuthenticated: boolean;
  user: { name: string } | null;
}

// Define the shape of the actions
export type Action =
  | {
      type: "LOGIN";
      payload: { name: string };
    }
  | { type: "LOGOUT" };

// Define the reducer function
const userReducer = (state: UserState, action: Action): UserState => {
  switch (action.type) {
    case "LOGIN":
      return {
        isAuthenticated: true,
        user: { name: action.payload.name },
      };
    case "LOGOUT":
      return {
        isAuthenticated: false,
        user: null,
      };
    default:
      return state;
  }
};

export default userReducer;
