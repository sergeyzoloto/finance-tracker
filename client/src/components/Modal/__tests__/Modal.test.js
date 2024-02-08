// FILEPATH: /home/serge/Projects/finance-tracker/client/src/components/Modal/Modal.test.js

import React from 'react';
import { render, fireEvent } from '@testing-library/react';
import Modal from '../Modal';

describe('Modal', () => {
  it('renders with the correct props', () => {
    const { getByPlaceholderText } = render(
      <Modal
        password="test password"
        setPassword={() => {}}
        handleConfirmDelete={() => {}}
        handleModalClose={() => {}}
      />,
    );

    expect(getByPlaceholderText('Enter password')).toBeInTheDocument();
  });

  it('calls setPassword when the input value changes', () => {
    const setPassword = jest.fn();
    const { getByPlaceholderText } = render(
      <Modal
        password="test password"
        setPassword={setPassword}
        handleConfirmDelete={() => {}}
        handleModalClose={() => {}}
      />,
    );

    fireEvent.change(getByPlaceholderText('Enter password'), {
      target: { value: 'new password' },
    });

    expect(setPassword).toHaveBeenCalledWith('new password');
  });

  it('calls handleConfirmDelete when the confirm button is clicked', () => {
    const handleConfirmDelete = jest.fn();
    const { getByText } = render(
      <Modal
        password="test password"
        setPassword={() => {}}
        handleConfirmDelete={handleConfirmDelete}
        handleModalClose={() => {}}
      />,
    );

    fireEvent.click(getByText('Confirm'));

    expect(handleConfirmDelete).toHaveBeenCalled();
  });

  it('calls handleModalClose when the cancel button is clicked', () => {
    const handleModalClose = jest.fn();
    const { getByText } = render(
      <Modal
        password="test password"
        setPassword={() => {}}
        handleConfirmDelete={() => {}}
        handleModalClose={handleModalClose}
      />,
    );

    fireEvent.click(getByText('Cancel'));

    expect(handleModalClose).toHaveBeenCalled();
  });
});
